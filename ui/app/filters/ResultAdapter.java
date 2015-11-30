package filters;

import play.api.libs.iteratee.Enumerator;
import play.api.mvc.ResponseHeader;
import play.api.mvc.Result;
import scala.Enumeration;
import scala.Tuple2;
import scala.collection.JavaConversions;
import scala.collection.mutable.Buffer;

import java.util.ArrayList;
import java.util.List;

@SuppressWarnings("serial")
public class ResultAdapter extends Result{

    private Result result;

    public ResultAdapter(Result result) {
        super(result.header(), result.body(), result.connection());
        this.result = result;
        if (result instanceof ResultAdapter) {
            throw new RuntimeException("Could not create ResultAdapter from ResultAdapter");
        }
    }

    public ResultAdapter WithHeader(String name, String value) {
        List<Tuple2<String, String>> headerList = new ArrayList<>();
        Tuple2<String, String> header = new Tuple2<>(name, value);
        headerList.add(header);
        Buffer<Tuple2<String, String>> headerBuffer = JavaConversions.asScalaBuffer(headerList);
        result = result.withHeaders(headerBuffer);

        return this;
    }

    @Override
    public ResponseHeader header() {
        return result.header();
    }

    @Override
    public Enumerator<byte[]> body() {
        return result.body();
    }

    @Override
    public Enumeration.Value connection() {
        return result.connection();
    }

    @Override
    public Object productElement(int n) {
        return result.productElement(n);
    }

    @Override
    public int productArity() {
        return result.productArity();
    }

    @Override
    public boolean canEqual(Object that) {
        return result.canEqual(that);
    }

    @Override
    public boolean equals(Object that) {
        return result.equals(that);
    }
}