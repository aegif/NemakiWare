import { SAMLConfig } from '../services/saml';

export const defaultSAMLConfig: SAMLConfig = {
  sso_url: 'https://demo.saml.provider.com/sso',
  entity_id: 'nemakiware-saml-client',
  callback_url: `${window.location.origin}/core/ui/saml-callback.html`,
  logout_url: `${window.location.origin}/core/ui/`
};

export const getSAMLConfig = (): SAMLConfig => {
  return {
    sso_url: 'http://localhost:8088/realms/nemakiware/protocol/saml',
    entity_id: 'nemakiware-saml-client',
    callback_url: `${window.location.origin}/core/ui/saml-callback.html`,
    logout_url: `${window.location.origin}/core/ui/`
  };
};

export const isSAMLEnabled = (): boolean => {
  return true;
};
