/*
 * #%L
 * Talend :: ESB :: Job :: Controller
 * %%
 * Copyright (C) 2011 Talend Inc.
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */
package org.talend.esb.job.controller.internal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import javax.xml.namespace.QName;
import javax.xml.soap.SOAPFault;
import javax.xml.transform.Source;
import javax.xml.ws.WebServiceException;
import javax.xml.ws.soap.SOAPFaultException;

import org.apache.cxf.Bus;
import org.apache.cxf.BusException;
import org.apache.cxf.databinding.source.SourceDataBinding;
import org.apache.cxf.endpoint.Client;
import org.apache.cxf.endpoint.Endpoint;
import org.apache.cxf.endpoint.EndpointException;
import org.apache.cxf.feature.AbstractFeature;
import org.apache.cxf.interceptor.Fault;
import org.apache.cxf.jaxws.JaxWsClientFactoryBean;
import org.apache.cxf.service.Service;
import org.apache.cxf.service.model.InterfaceInfo;
import org.apache.cxf.service.model.ServiceInfo;
import org.apache.cxf.ws.security.SecurityConstants;
import org.apache.cxf.ws.security.trust.STSClient;
import org.talend.esb.job.controller.internal.util.DOM4JMarshaller;
import org.talend.esb.job.controller.internal.util.ServiceHelper;
import org.talend.esb.sam.agent.feature.EventFeature;
import org.talend.esb.sam.common.handler.impl.CustomInfoHandler;

import routines.system.api.ESBConsumer;

@javax.jws.WebService()
public class RuntimeESBConsumer implements ESBConsumer {
    private static final Logger LOG =
        Logger.getLogger(RuntimeESBConsumer.class.getName());

    private final QName serviceName;
    private final QName portName;
    private final String operationName;
    private final String publishedEndpointUrl;
    private final boolean isRequestResponse;
    private final AbstractFeature serviceLocator;
    private final EventFeature eventFeature;
    private final Bus bus;

    private Client client;
    private boolean isAuthenticationRequired = false;

    public RuntimeESBConsumer(
            final QName serviceName,
            final QName portName,
            String operationName,
            String publishedEndpointUrl,
            boolean isRequestResponse,
            final AbstractFeature serviceLocator,
            final EventFeature eventFeature,
            final Bus bus) {
        this.serviceName = serviceName;
        this.portName = portName;
        this.operationName = operationName;
        this.publishedEndpointUrl = publishedEndpointUrl;
        this.isRequestResponse = isRequestResponse;
        this.serviceLocator = serviceLocator;
        this.eventFeature = eventFeature;
        this.bus = bus;
    }

    @Override
    public Object invoke(Object payload) throws Exception {
        if (payload instanceof org.dom4j.Document) {
            return sendDocument((org.dom4j.Document)payload);
        } else if (payload instanceof java.util.Map) {
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> map =
                (java.util.Map<String, Object>)payload;

            @SuppressWarnings("unchecked")
            java.util.Map<String, String> samProps =
                (java.util.Map<String, String>)map.get(ESBProviderBase.REQUEST_SAM_PROPS);
            if (samProps != null && eventFeature != null) {
                LOG.info("SAM custom properties received: " + samProps);
                //System.out.println("Consumer/" + "SAM custom properties received: " + samProps);
                CustomInfoHandler ciHandler = new CustomInfoHandler();
                ciHandler.setCustomInfo(samProps);
                eventFeature.setHandler(ciHandler);
            }

            return sendDocument(
                (org.dom4j.Document)map.get(ESBProviderBase.REQUEST_PAYLOAD));
        } else {
            throw new RuntimeException(
                "Consumer try to send incompatible object: " + payload.getClass().getName());
        }
    }

    private Object sendDocument(org.dom4j.Document doc) throws Exception {
        Client client = createClient();
        try {
            Object[] result = client.invoke(operationName,
                    DOM4JMarshaller.documentToSource(doc));
            if (result != null) {
                return DOM4JMarshaller.sourceToDocument((Source)result[0]);
            }
        } catch (org.apache.cxf.binding.soap.SoapFault e) {
            SOAPFault soapFault = ServiceHelper.createSoapFault(e);
            if (soapFault == null) {
                throw new WebServiceException(e);
            }
            SOAPFaultException exception = new SOAPFaultException(soapFault);
            if (e instanceof Fault && e.getCause() != null) {
                exception.initCause(e.getCause());
            } else {
                exception.initCause(e);
            }
            throw exception;
        }
        return null;
    }

    private Client createClient() throws BusException, EndpointException {
        if (client != null) {
            return client;
        }
        final JaxWsClientFactoryBean cf = new JaxWsClientFactoryBean() {
            @Override
            protected Endpoint createEndpoint() throws BusException,
                    EndpointException {
                final Endpoint endpoint = super.createEndpoint();
                // set portType = serviceName
                InterfaceInfo ii =
                    endpoint.getService().getServiceInfos().get(0).getInterface();
                ii.setName(serviceName);
                return endpoint;
            }
        };
        cf.setServiceName(serviceName);
        cf.setEndpointName(portName);
        final String endpointUrl =
            (serviceLocator == null)
                ? publishedEndpointUrl
                : "locator://" + serviceName.getLocalPart();
        cf.setAddress(endpointUrl);
        cf.setServiceClass(this.getClass());
        cf.setBus(bus);
        List<AbstractFeature> features = new ArrayList<AbstractFeature>();
        if (serviceLocator != null) {
            features.add(serviceLocator);
        }
        if (eventFeature != null) {
            features.add(eventFeature);
        }
        cf.setFeatures(features);

        if (isAuthenticationRequired) {
            STSClient stsClient = new STSClient(bus);

            Map<String, Object> stsProperties = new HashMap<String, Object>();
            stsProperties.put(SecurityConstants.USERNAME, "");
            stsProperties.put(SecurityConstants.CALLBACK_HANDLER, "");
            stsClient.setProperties(stsProperties);

            cf.setProperties(
                Collections.singletonMap(SecurityConstants.STS_CLIENT, (Object)stsClient));
        }

        client = cf.create();

        final Service service = client.getEndpoint().getService();
        service.setDataBinding(new SourceDataBinding());

        final ServiceInfo si = service.getServiceInfos().get(0);
        ServiceHelper.addOperation(si,
                operationName, isRequestResponse);

        return client;
    }

    public void destroy() {
        if (client != null) {
            client.destroy();
        }
    }

}
