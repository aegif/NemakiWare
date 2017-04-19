package global;

import play.Logger;
import play.http.HttpErrorHandler;
import play.mvc.*;
import play.mvc.Http.*;
import util.Util;
import play.libs.F.*;
import views.html.*;

public class ErrorHandler implements HttpErrorHandler {

	@Override
    public Promise<Result> onClientError(RequestHeader request, int statusCode, String message) {
        Logger.debug("onClientError");
        String repositoryId = Util.getRepositoryId(request.uri());
        return Promise.<Result> pure(
                Results.badRequest(error.render(repositoryId))
        );
    }
	@Override
    public Promise<Result> onServerError(RequestHeader request, Throwable exception) {
        Logger.debug("onServerError");
        return Promise.<Result> pure(
                Results.internalServerError("A server error occurred: " + exception.getMessage())
        );
    }


}
