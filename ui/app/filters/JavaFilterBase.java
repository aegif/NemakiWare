package filters;

import play.api.mvc.*;
import scala.Function1;
import scala.concurrent.Future;
import scala.runtime.AbstractFunction1;

public abstract class JavaFilterBase implements Filter {

    @Override
    public Future<Result> apply(Function1<RequestHeader, Future<Result>> nextFilter, final RequestHeader requestHeader) {
        return nextFilter
                .apply(requestHeader)
                .map(new AbstractFunction1<Result, Result>() {
                         @Override
                         public Result apply(Result currentResult) {
                             return Apply(currentResult, requestHeader);
                         }
                     },
                        play.api.libs.concurrent.Execution.defaultContext());
    }

    @Override
    public EssentialAction apply(EssentialAction next) {
        return Filter$class.apply(this, next);
    }

    public abstract Result Apply(Result currentResult, RequestHeader requestHeader);

}
