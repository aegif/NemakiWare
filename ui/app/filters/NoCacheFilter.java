package filters;

import play.api.mvc.RequestHeader;
import play.api.mvc.Result;

public class NoCacheFilter extends JavaFilterBase {

    @Override
    public Result Apply(Result currentResult, RequestHeader requestHeader) {
        //if (requestHeader.headers().get("X-Filter").isDefined()) {
            ResultAdapter resultAdapter = new ResultAdapter(currentResult);

            // for IE no cache
            return resultAdapter
	            .WithHeader("Cache-Control",  "no-cache, no-store, must-revalidate, max-age=0, post-check=0, pre-check=0") // HTTP 1.1
	            .WithHeader("Pragma", "no-cache") // HTTP 1.0.
	            .WithHeader("EXPIRES", "0") // Proxies
	            ;
        //}
        //return currentResult;
    }
}