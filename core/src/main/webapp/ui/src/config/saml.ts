import { SAMLConfig } from '../services/saml';

export const defaultSAMLConfig: SAMLConfig = {
  sso_url: 'https://demo.saml.provider.com/sso',
  entity_id: 'nemakiware-spa',
  callback_url: `${window.location.origin}/core/ui/saml-callback`,
  logout_url: `${window.location.origin}/core/ui/`
};

export const getSAMLConfig = (): SAMLConfig => {
  return {
    sso_url: '/core/samlLogin', // Use existing Play Framework SAML endpoint
    entity_id: 'nemakiware-spa',
    callback_url: `${window.location.origin}/core/ui/saml-callback`,
    logout_url: `${window.location.origin}/core/ui/`
  };
};

export const isSAMLEnabled = (): boolean => {
  return true;
};
