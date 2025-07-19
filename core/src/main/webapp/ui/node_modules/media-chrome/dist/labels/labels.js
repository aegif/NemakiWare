import { t } from "../utils/i18n.js";
const defaultErrorTitles = {
  2: t("Network Error"),
  3: t("Decode Error"),
  4: t("Source Not Supported"),
  5: t("Encryption Error")
};
const defaultErrorMessages = {
  2: t("A network error caused the media download to fail."),
  3: t(
    "A media error caused playback to be aborted. The media could be corrupt or your browser does not support this format."
  ),
  4: t(
    "An unsupported error occurred. The server or network failed, or your browser does not support this format."
  ),
  5: t("The media is encrypted and there are no keys to decrypt it.")
};
const formatError = (error) => {
  var _a, _b;
  if (error.code === 1)
    return null;
  return {
    title: (_a = defaultErrorTitles[error.code]) != null ? _a : `Error ${error.code}`,
    message: (_b = defaultErrorMessages[error.code]) != null ? _b : error.message
  };
};
export {
  formatError
};
