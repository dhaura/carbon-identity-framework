/*
 * Copyright (c) 2024, WSO2 LLC. (http://www.wso2.com).
 *
 * WSO2 LLC. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.carbon.identity.core;

import org.apache.axiom.om.OMElement;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.CarbonException;
import org.wso2.carbon.base.MultitenantConstants;
import org.wso2.carbon.core.RegistryResources;
import org.wso2.carbon.core.util.KeyStoreManager;
import org.wso2.carbon.core.util.KeyStoreUtil;
import org.wso2.carbon.identity.core.model.IdentityKeyStoreMapping;
import org.wso2.carbon.identity.core.util.IdentityConfigParser;
import org.wso2.carbon.identity.core.util.IdentityKeyStoreResolverConstants.InboundProtocol;
import org.wso2.carbon.identity.core.util.IdentityKeyStoreResolverException;
import org.wso2.carbon.identity.core.util.IdentityKeyStoreResolverUtil;
import org.wso2.carbon.identity.core.util.IdentityTenantUtil;
import org.wso2.carbon.utils.CarbonUtils;

import java.io.File;
import java.security.Key;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.interfaces.RSAPublicKey;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.wso2.carbon.identity.core.util.IdentityKeyStoreResolverConstants.ATTR_NAME_KEYSTORE_NAME;
import static org.wso2.carbon.identity.core.util.IdentityKeyStoreResolverConstants.ATTR_NAME_PROTOCOL;
import static org.wso2.carbon.identity.core.util.IdentityKeyStoreResolverConstants.ATTR_NAME_USE_IN_ALL_TENANTS;
import static org.wso2.carbon.identity.core.util.IdentityKeyStoreResolverConstants.CONFIG_ELEM_KEYSTORE_MAPPING;
import static org.wso2.carbon.identity.core.util.IdentityKeyStoreResolverConstants.CONFIG_ELEM_KEYSTORE_MAPPINGS;
import static org.wso2.carbon.identity.core.util.IdentityKeyStoreResolverConstants.CONFIG_ELEM_SECURITY;
import static org.wso2.carbon.identity.core.util.IdentityKeyStoreResolverConstants.ErrorMessages;


/**
 * KeyStore manager for inbound authentication protocols.
 * Retrieve keystores, private keys, public keys and public certificates.
 */
public class IdentityKeyStoreResolver {

    private static IdentityKeyStoreResolver instance = null;

    private static ConcurrentHashMap<InboundProtocol, IdentityKeyStoreMapping>
            keyStoreMappings = new ConcurrentHashMap<>();

    // Hashmaps to store retrieved private keys and certificates.
    // This will reduce the time required to read configs and load data from keystores everytime.
    private static Map<String, Key> privateKeys = new ConcurrentHashMap<>();
    private static Map<String, Certificate> publicCerts = new ConcurrentHashMap<>();

    private static final Log LOG = LogFactory.getLog(IdentityKeyStoreResolver.class);

    private IdentityKeyStoreResolver() {

        parseIdentityKeyStoreMappingConfigs();
    }

    public static IdentityKeyStoreResolver getInstance() {

        if (instance == null) {
            instance = new IdentityKeyStoreResolver();
        }
        return instance;
    }

    /**
     * Return Primary or tenant keystore according to given tenant domain.
     *
     * @param tenantDomain  Tenant domain
     * @return Primary or tenant keystore
     * @throws IdentityKeyStoreResolverException the exception in the IdentityKeyStoreResolver class
     */
    private KeyStore getKeyStore(String tenantDomain) throws IdentityKeyStoreResolverException {

        int tenantId = IdentityTenantUtil.getTenantId(tenantDomain);
        KeyStoreManager keyStoreManager = KeyStoreManager.getInstance(tenantId);
        try {
            if (MultitenantConstants.SUPER_TENANT_DOMAIN_NAME.equals(tenantDomain)) {
                // Get primary keystore from keyStoreManager
                return keyStoreManager.getPrimaryKeyStore();
            }

            // Get tenant keystore from keyStoreManager
            String tenantKeyStoreName = IdentityKeyStoreResolverUtil.buildTenantKeyStoreName(tenantDomain);
            return keyStoreManager.getKeyStore(tenantKeyStoreName);
        } catch (Exception e) {
            throw new IdentityKeyStoreResolverException(
                    ErrorMessages.ERROR_CODE_ERROR_RETRIEVING_TENANT_KEYSTORE.getCode(),
                    String.format(ErrorMessages.ERROR_CODE_ERROR_RETRIEVING_TENANT_KEYSTORE.getDescription(),
                            tenantDomain), e);
        }
    }

    /**
     * Return Primary, tenant or custom keystore.
     *
     * @param tenantDomain      Tenant domain
     * @param inboundProtocol   Inbound authentication protocol of the application
     * @return Primary, tenant or custom keystore
     * @throws IdentityKeyStoreResolverException the exception in the IdentityKeyStoreResolver class
     */
    public KeyStore getKeyStore(String tenantDomain, InboundProtocol inboundProtocol)
            throws IdentityKeyStoreResolverException {

        if (StringUtils.isEmpty(tenantDomain)) {
            throw new IdentityKeyStoreResolverException(
                    ErrorMessages.ERROR_CODE_INVALID_ARGUMENT.getCode(),
                    String.format(ErrorMessages.ERROR_CODE_INVALID_ARGUMENT.getDescription(), "Tenant domain"));
        }
        if (inboundProtocol == null) {
            throw new IdentityKeyStoreResolverException(
                    ErrorMessages.ERROR_CODE_INVALID_ARGUMENT.getCode(),
                    String.format(ErrorMessages.ERROR_CODE_INVALID_ARGUMENT.getDescription(), "Inbound protocol"));
        }

        if (keyStoreMappings.containsKey(inboundProtocol)) {
            if (MultitenantConstants.SUPER_TENANT_DOMAIN_NAME.equals(tenantDomain) ||
                    keyStoreMappings.get(inboundProtocol).getUseInAllTenants()) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Custom keystore configuration avialble for " + inboundProtocol + " protocol.");
                }

                String keyStoreName = IdentityKeyStoreResolverUtil.buildCustomKeyStoreName(
                        keyStoreMappings.get(inboundProtocol).getKeyStoreName());

                try {
                    int tenantId = IdentityTenantUtil.getTenantId(tenantDomain);
                    KeyStoreManager keyStoreManager = KeyStoreManager.getInstance(tenantId);
                    return keyStoreManager.getKeyStore(keyStoreName);
                } catch (Exception e) {
                    throw new IdentityKeyStoreResolverException(
                            ErrorMessages.ERROR_CODE_ERROR_RETRIEVING_CUSTOM_KEYSTORE.getCode(),
                            String.format(ErrorMessages.ERROR_CODE_ERROR_RETRIEVING_CUSTOM_KEYSTORE.getDescription(),
                                    keyStoreName), e);
                }
            }
        }

        return getKeyStore(tenantDomain);
    }

    /**
     * Return Primary key of the Primary or tenant keystore according to given tenant domain.
     *
     * @param tenantDomain  Tenant domain
     * @return Primary key of Primary or tenant keystore
     * @throws IdentityKeyStoreResolverException the exception in the IdentityKeyStoreResolver class
     */
    private Key getPrivateKey(String tenantDomain) throws IdentityKeyStoreResolverException {

        int tenantId = IdentityTenantUtil.getTenantId(tenantDomain);
        if (privateKeys.containsKey(String.valueOf(tenantId))) {
            return privateKeys.get(String.valueOf(tenantId));
        }

        KeyStoreManager keyStoreManager = KeyStoreManager.getInstance(tenantId);
        Key privateKey;

        try {
            if (MultitenantConstants.SUPER_TENANT_DOMAIN_NAME.equals(tenantDomain)) {
                privateKey = keyStoreManager.getDefaultPrivateKey();
            } else {
                String tenantKeyStoreName = IdentityKeyStoreResolverUtil.buildTenantKeyStoreName(tenantDomain);
                privateKey = keyStoreManager.getPrivateKey(tenantKeyStoreName, tenantDomain);
            }
        } catch (Exception e) {
            throw new IdentityKeyStoreResolverException(
                    ErrorMessages.ERROR_CODE_ERROR_RETRIEVING_TENANT_PRIVATE_KEY.getCode(),
                    String.format(ErrorMessages.ERROR_CODE_ERROR_RETRIEVING_TENANT_PRIVATE_KEY.getDescription(),
                            tenantDomain), e);
        }

        privateKeys.put(String.valueOf(tenantId), privateKey);
        return privateKey;
    }

    /**
     * Return Private Key of the Primary, tenant or custom keystore.
     *
     * @param tenantDomain      Tenant domain
     * @param inboundProtocol   Inbound authentication protocol of the application
     * @return Private Key of the Primary, tenant or custom keystore
     * @throws IdentityKeyStoreResolverException the exception in the IdentityKeyStoreResolver class
     */
    public Key getPrivateKey(String tenantDomain, InboundProtocol inboundProtocol)
            throws IdentityKeyStoreResolverException {

        if (StringUtils.isEmpty(tenantDomain)) {
            throw new IdentityKeyStoreResolverException(
                    ErrorMessages.ERROR_CODE_INVALID_ARGUMENT.getCode(),
                    String.format(ErrorMessages.ERROR_CODE_INVALID_ARGUMENT.getDescription(), "Tenant domain"));
        }
        if (inboundProtocol == null) {
            throw new IdentityKeyStoreResolverException(
                    ErrorMessages.ERROR_CODE_INVALID_ARGUMENT.getCode(),
                    String.format(ErrorMessages.ERROR_CODE_INVALID_ARGUMENT.getDescription(), "Inbound protocol"));
        }

        if (keyStoreMappings.containsKey(inboundProtocol)) {
            if (MultitenantConstants.SUPER_TENANT_DOMAIN_NAME.equals(tenantDomain) ||
                    keyStoreMappings.get(inboundProtocol).getUseInAllTenants()) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Custom keystore configuration availble for " + inboundProtocol + " protocol.");
                }

                if (privateKeys.containsKey(inboundProtocol.toString())) {
                    return privateKeys.get(inboundProtocol.toString());
                }

                String keyStoreName = IdentityKeyStoreResolverUtil.buildCustomKeyStoreName(
                        keyStoreMappings.get(inboundProtocol).getKeyStoreName());

                try {
                    int tenantId = IdentityTenantUtil.getTenantId(tenantDomain);
                    KeyStoreManager keyStoreManager = KeyStoreManager.getInstance(tenantId);
                    Key privateKey = keyStoreManager.getPrivateKey(keyStoreName, null);
                    privateKeys.put(inboundProtocol.toString(), privateKey);
                    return privateKey;
                } catch (Exception e) {
                    throw new IdentityKeyStoreResolverException(
                            ErrorMessages.ERROR_CODE_ERROR_RETRIEVING_CUSTOM_PRIVATE_KEY.getCode(),
                            String.format(ErrorMessages.ERROR_CODE_ERROR_RETRIEVING_CUSTOM_PRIVATE_KEY.getDescription(),
                                    inboundProtocol), e);
                }
            }
        }
        return getPrivateKey(tenantDomain);
    }

    /**
     * Return Public Certificate of the Primary or tenant keystore according to given tenant domain.
     *
     * @param tenantDomain  Tenant domain
     * @return Public Certificate of Primary or tenant keystore
     * @throws IdentityKeyStoreResolverException the exception in the IdentityKeyStoreResolver class
     */
    private Certificate getCertificate(String tenantDomain) throws IdentityKeyStoreResolverException {

        int tenantId = IdentityTenantUtil.getTenantId(tenantDomain);
        if (publicCerts.containsKey(String.valueOf(tenantId))) {
            return publicCerts.get(String.valueOf(tenantId));
        }

        KeyStoreManager keyStoreManager = KeyStoreManager.getInstance(tenantId);
        Certificate publicCert;
        try {
            if (MultitenantConstants.SUPER_TENANT_DOMAIN_NAME.equals(tenantDomain)) {
                publicCert = keyStoreManager.getDefaultPrimaryCertificate();
            } else {
                String tenantKeyStoreName = IdentityKeyStoreResolverUtil.buildTenantKeyStoreName(tenantDomain);
                publicCert = keyStoreManager.getCertificate(tenantKeyStoreName, tenantDomain);
            }
        } catch (Exception e) {
            throw new IdentityKeyStoreResolverException(
                    ErrorMessages.ERROR_CODE_ERROR_RETRIEVING_TENANT_PUBLIC_CERTIFICATE.getCode(),
                    String.format(ErrorMessages.ERROR_CODE_ERROR_RETRIEVING_TENANT_PUBLIC_CERTIFICATE.getDescription(),
                            tenantDomain), e);
        }

        publicCerts.put(String.valueOf(tenantId), publicCert);
        return publicCert;
    }

    /**
     * Return Public Certificate of the Primary, tenant or custom keystore.
     *
     * @param tenantDomain      Tenant domain
     * @param inboundProtocol   Inbound authentication protocol of the application
     * @return Public Certificate of the Primary, tenant or custom keystore
     * @throws IdentityKeyStoreResolverException the exception in the IdentityKeyStoreResolver class
     */
    public Certificate getCertificate(String tenantDomain, InboundProtocol inboundProtocol)
            throws IdentityKeyStoreResolverException {

        if (StringUtils.isEmpty(tenantDomain)) {
            throw new IdentityKeyStoreResolverException(
                    ErrorMessages.ERROR_CODE_INVALID_ARGUMENT.getCode(),
                    String.format(ErrorMessages.ERROR_CODE_INVALID_ARGUMENT.getDescription(), "Tenant domain"));
        }
        if (inboundProtocol == null) {
            throw new IdentityKeyStoreResolverException(
                    ErrorMessages.ERROR_CODE_INVALID_ARGUMENT.getCode(),
                    String.format(ErrorMessages.ERROR_CODE_INVALID_ARGUMENT.getDescription(), "Inbound protocol"));
        }

        if (keyStoreMappings.containsKey(inboundProtocol)) {
            if (MultitenantConstants.SUPER_TENANT_DOMAIN_NAME.equals(tenantDomain) ||
                    keyStoreMappings.get(inboundProtocol).getUseInAllTenants()) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Custom keystore configuration for " + inboundProtocol + " protocol.");
                }

                if (publicCerts.containsKey(inboundProtocol.toString())) {
                    return publicCerts.get(inboundProtocol.toString());
                }

                String keyStoreName = IdentityKeyStoreResolverUtil.buildCustomKeyStoreName(
                        keyStoreMappings.get(inboundProtocol).getKeyStoreName());

                try {
                    int tenantId = IdentityTenantUtil.getTenantId(tenantDomain);
                    KeyStoreManager keyStoreManager = KeyStoreManager.getInstance(tenantId);
                    Certificate publicCert = keyStoreManager.getCertificate(keyStoreName, null);
                    publicCerts.put(inboundProtocol.toString(), publicCert);
                    return publicCert;
                } catch (Exception e) {
                    throw new IdentityKeyStoreResolverException(
                            ErrorMessages.ERROR_CODE_ERROR_RETRIEVING_CUSTOM_PUBLIC_CERTIFICATE.getCode(),
                            String.format(ErrorMessages.ERROR_CODE_ERROR_RETRIEVING_CUSTOM_PUBLIC_CERTIFICATE
                                            .getDescription(), tenantDomain), e);
                }
            }
        }
        return getCertificate(tenantDomain);
    }

    /**
     * Return Public Key of the Primary or tenant keystore according to given tenant domain.
     *
     * @param tenantDomain  Tenant domain
     * @return Public Key of Primary or tenant keystore
     * @throws IdentityKeyStoreResolverException the exception in the IdentityKeyStoreResolver class
     */
    private RSAPublicKey getPublicKey(String tenantDomain) throws IdentityKeyStoreResolverException {

        return (RSAPublicKey) getCertificate(tenantDomain).getPublicKey();
    }

    /**
     * Return Public Key of the Primary, tenant or custom keystore.
     *
     * @param tenantDomain      Tenant domain
     * @param inboundProtocol   Inbound authentication protocol of the application
     * @return Public Key of the Primary, tenant or custom keystore
     * @throws IdentityKeyStoreResolverException the exception in the IdentityKeyStoreResolver class
     */
    public RSAPublicKey getPublicKey(String tenantDomain, InboundProtocol inboundProtocol)
            throws IdentityKeyStoreResolverException {

        // Conditions are checked in getCertificate method
        return (RSAPublicKey) getCertificate(tenantDomain, inboundProtocol).getPublicKey();
    }

    /**
     * Return keystore name of the Primary, tenant or custom keystore.
     *
     * @param tenantDomain      Tenant domain
     * @param inboundProtocol   Inbound authentication protocol of the application
     * @return Keystore name of the Primary, tenant or custom keystore
     * @throws IdentityKeyStoreResolverException the exception in the IdentityKeyStoreResolver class
     */
    public String getKeyStoreName(String tenantDomain, InboundProtocol inboundProtocol)
            throws IdentityKeyStoreResolverException {

        if (StringUtils.isEmpty(tenantDomain)) {
            throw new IdentityKeyStoreResolverException(
                    ErrorMessages.ERROR_CODE_INVALID_ARGUMENT.getCode(),
                    String.format(ErrorMessages.ERROR_CODE_INVALID_ARGUMENT.getDescription(), "Tenant domain"));
        }
        if (inboundProtocol == null) {
            throw new IdentityKeyStoreResolverException(
                    ErrorMessages.ERROR_CODE_INVALID_ARGUMENT.getCode(),
                    String.format(ErrorMessages.ERROR_CODE_INVALID_ARGUMENT.getDescription(), "Inbound protocol"));
        }

        if (keyStoreMappings.containsKey(inboundProtocol)) {
            if (MultitenantConstants.SUPER_TENANT_DOMAIN_NAME.equals(tenantDomain) ||
                    keyStoreMappings.get(inboundProtocol).getUseInAllTenants()) {

                return IdentityKeyStoreResolverUtil.buildCustomKeyStoreName(
                        keyStoreMappings.get(inboundProtocol).getKeyStoreName());
            }
        }

        if (MultitenantConstants.SUPER_TENANT_DOMAIN_NAME.equals(tenantDomain)) {
            try {
                File keyStoreFile = new File(getPrimaryKeyStoreConfig(
                        RegistryResources.SecurityManagement.CustomKeyStore.PROP_LOCATION));
                return keyStoreFile.getName();
            } catch (Exception e) {
                throw new IdentityKeyStoreResolverException(
                        ErrorMessages.ERROR_CODE_ERROR_RETRIEVING_PRIMARY_KEYSTORE_CONFIGURATION.getCode(),
                        ErrorMessages.ERROR_CODE_ERROR_RETRIEVING_PRIMARY_KEYSTORE_CONFIGURATION.getDescription(), e);
            }
        }

        return IdentityKeyStoreResolverUtil.buildTenantKeyStoreName(tenantDomain);
    }

    /**
     * Return key store configs of the Primary, tenant or custom keystore.
     *
     * @param tenantDomain      Tenant domain
     * @param inboundProtocol   Inbound authentication protocol of the application
     * @param configName        Name of the configuration needed
     * @return Configuration value
     * @throws IdentityKeyStoreResolverException the exception in the IdentityKeyStoreResolver class
     */
    public String getKeyStoreConfig(String tenantDomain, InboundProtocol inboundProtocol, String configName)
            throws IdentityKeyStoreResolverException {

        if (StringUtils.isEmpty(tenantDomain)) {
            throw new IdentityKeyStoreResolverException(
                    ErrorMessages.ERROR_CODE_INVALID_ARGUMENT.getCode(),
                    String.format(ErrorMessages.ERROR_CODE_INVALID_ARGUMENT.getDescription(), "Tenant domain"));
        }
        if (inboundProtocol == null) {
            throw new IdentityKeyStoreResolverException(
                    ErrorMessages.ERROR_CODE_INVALID_ARGUMENT.getCode(),
                    String.format(ErrorMessages.ERROR_CODE_INVALID_ARGUMENT.getDescription(), "Inbound protocol"));
        }
        if (StringUtils.isEmpty(configName)) {
            throw new IdentityKeyStoreResolverException(
                    ErrorMessages.ERROR_CODE_INVALID_ARGUMENT.getCode(),
                    String.format(ErrorMessages.ERROR_CODE_INVALID_ARGUMENT.getDescription(), "Config name"));
        }

        if (keyStoreMappings.containsKey(inboundProtocol)) {
            if (MultitenantConstants.SUPER_TENANT_DOMAIN_NAME.equals(tenantDomain) ||
                    keyStoreMappings.get(inboundProtocol).getUseInAllTenants()) {

                String keyStoreName = IdentityKeyStoreResolverUtil.buildCustomKeyStoreName(
                        keyStoreMappings.get(inboundProtocol).getKeyStoreName());
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Retreiving " + configName + " configuration for keystore " + keyStoreName);
                }

                return getCustomKeyStoreConfig(keyStoreName, configName);
            }
        }

        if (MultitenantConstants.SUPER_TENANT_DOMAIN_NAME.equals(tenantDomain)) {
            return getPrimaryKeyStoreConfig(configName);
        }

        return getTenantKeyStoreConfig(tenantDomain, configName);

    }

    private String getPrimaryKeyStoreConfig(String configName) throws IdentityKeyStoreResolverException {

        try {
            KeyStoreUtil.validateKeyStoreConfigName(configName);

            String fullConfigPath = "Security.KeyStore." + configName;
            return CarbonUtils.getServerConfiguration().getFirstProperty(fullConfigPath);
        } catch (CarbonException e) {
            throw new IdentityKeyStoreResolverException(
                    ErrorMessages.ERROR_CODE_ERROR_RETRIEVING_PRIMARY_KEYSTORE_CONFIGURATION.getCode(),
                    ErrorMessages.ERROR_CODE_ERROR_RETRIEVING_PRIMARY_KEYSTORE_CONFIGURATION.getDescription(), e);
        }
    }

    private String getTenantKeyStoreConfig(String tenantDomain, String configName)
            throws IdentityKeyStoreResolverException {

        try {
            KeyStoreUtil.validateKeyStoreConfigName(configName);

            switch (configName) {
                case (RegistryResources.SecurityManagement.CustomKeyStore.PROP_LOCATION):
                    // Returning only key store name because tenant key stores reside within the registry.
                    return IdentityKeyStoreResolverUtil.buildTenantKeyStoreName(tenantDomain);
                case (RegistryResources.SecurityManagement.CustomKeyStore.PROP_TYPE):
                    return CarbonUtils.getServerConfiguration().getFirstProperty(
                            RegistryResources.SecurityManagement.PROP_TYPE);
                case (RegistryResources.SecurityManagement.CustomKeyStore.PROP_PASSWORD):
                case (RegistryResources.SecurityManagement.CustomKeyStore.PROP_KEY_PASSWORD):
                    return CarbonUtils.getServerConfiguration().getFirstProperty(
                            RegistryResources.SecurityManagement.PROP_PASSWORD);
                case (RegistryResources.SecurityManagement.CustomKeyStore.PROP_KEY_ALIAS):
                    return tenantDomain;
                default:
                    // This state is not possible since config name is validated above.
                    throw new IdentityKeyStoreResolverException(
                            ErrorMessages.ERROR_CODE_ERROR_RETRIEVING_TENANT_KEYSTORE_CONFIGURATION.getCode(),
                            String.format(ErrorMessages.ERROR_CODE_ERROR_RETRIEVING_TENANT_KEYSTORE_CONFIGURATION
                                    .getDescription(), tenantDomain));
            }
        } catch (CarbonException e) {
            throw new IdentityKeyStoreResolverException(
                    ErrorMessages.ERROR_CODE_ERROR_RETRIEVING_TENANT_KEYSTORE_CONFIGURATION.getCode(),
                    String.format(ErrorMessages.ERROR_CODE_ERROR_RETRIEVING_TENANT_KEYSTORE_CONFIGURATION
                            .getDescription(), tenantDomain), e);
        }
    }

    private String getCustomKeyStoreConfig(String keyStoreName, String configName)
            throws IdentityKeyStoreResolverException {

        try {
            KeyStoreUtil.validateKeyStoreConfigName(configName);

            OMElement configElement = KeyStoreUtil
                    .getCustomKeyStoreConfigElement(keyStoreName, CarbonUtils.getServerConfiguration());
            return KeyStoreUtil.getCustomKeyStoreConfig(configElement, configName);
        } catch (CarbonException e) {
            throw new IdentityKeyStoreResolverException(
                    ErrorMessages.ERROR_CODE_ERROR_RETRIEVING_CUSTOM_KEYSTORE_CONFIGURATION.getCode(),
                    String.format(ErrorMessages.ERROR_CODE_ERROR_RETRIEVING_CUSTOM_KEYSTORE_CONFIGURATION
                            .getDescription(), keyStoreName), e);
        }
    }

    private void parseIdentityKeyStoreMappingConfigs() {

        OMElement securityElem = IdentityConfigParser.getInstance().getConfigElement(
                CONFIG_ELEM_SECURITY);
        OMElement keyStoreMappingsElem = securityElem.getFirstChildWithName(
                IdentityKeyStoreResolverUtil.getQNameWithIdentityNameSpace(
                        CONFIG_ELEM_KEYSTORE_MAPPINGS));

        if (keyStoreMappingsElem == null) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("No CustomKeyStoreMapping configurations found.");
            }
            return;
        }

        Iterator<OMElement> iterator = keyStoreMappingsElem.getChildrenWithName(
                IdentityKeyStoreResolverUtil.getQNameWithIdentityNameSpace(
                        CONFIG_ELEM_KEYSTORE_MAPPING));
        while (iterator.hasNext()) {
            OMElement keyStoreMapping = iterator.next();

            // Parse inbound protocol
            OMElement protocolElement = keyStoreMapping.getFirstChildWithName(
                    IdentityKeyStoreResolverUtil.getQNameWithIdentityNameSpace(
                            ATTR_NAME_PROTOCOL));
            if (protocolElement == null) {
                LOG.error("Error occurred when reading configuration. CustomKeyStoreMapping Protocol value null.");
                continue;
            }

            // Parse inbound protocol name
            InboundProtocol protocol = InboundProtocol.fromString(protocolElement.getText());
            if (protocol == null) {
                LOG.warn("Invalid authentication protocol configuration in CustomKeyStoreMappings. Config ignored.");
                continue;
            }
            if (keyStoreMappings.containsKey(protocol)) {
                LOG.warn("Multiple CustomKeyStoreMappings configured for " + protocol.toString() +
                        " protocol. Second config ignored.");
                continue;
            }
            // TODO: Remove after SAML implementation
            if (protocol == InboundProtocol.SAML) {
                LOG.warn("CustomKeyStoreMapping for SAML configured. This is not supported yet." +
                        " Please use [keystore.saml] configuration.");
            }

            // Parse keystore name
            OMElement keyStoreNameElement = keyStoreMapping.getFirstChildWithName(
                    IdentityKeyStoreResolverUtil.getQNameWithIdentityNameSpace(
                            ATTR_NAME_KEYSTORE_NAME));
            if (keyStoreNameElement == null) {
                LOG.error("Error occurred when reading configuration. CustomKeyStoreMapping KeyStoreName value null.");
                continue;
            }
            String keyStoreName = keyStoreNameElement.getText();

            // Parse UseInAllTenants config
            OMElement useInAllTenantsElement = keyStoreMapping.getFirstChildWithName(
                    IdentityKeyStoreResolverUtil.getQNameWithIdentityNameSpace(
                            ATTR_NAME_USE_IN_ALL_TENANTS));
            if (useInAllTenantsElement == null) {
                LOG.error("Error occurred when reading configuration. " +
                        "CustomKeyStoreMapping useInAllTenants value null.");
                continue;
            }
            Boolean useInAllTenants = Boolean.valueOf(useInAllTenantsElement.getText());

            // Add custom keystore mapping to the map
            IdentityKeyStoreMapping identityKeyStoreMapping = new IdentityKeyStoreMapping(
                    keyStoreName, protocol, useInAllTenants);
            keyStoreMappings.put(protocol, identityKeyStoreMapping);
        }
    }
}
