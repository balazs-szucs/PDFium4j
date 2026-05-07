import java.util.function.Supplier;
import java.lang.reflect.StableValue;

public class TestStableValue {
    public static void main(String[] args) {
        Supplier<String> s = StableValue.supplier(() -> "hello");
        System.out.println(s.get());
    }
}
