@org.checkerframework.framework.qual.SubtypeOf
private @interface foo {}

public class Foo {
    private void baz()
    {
        @foo long test1 = (@foo long) 0;

        if (!(@foo long) 2 == test1)
        {
        }
    }
}