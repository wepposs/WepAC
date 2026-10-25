public class NestedNameTest {
    static class Outer { static class Inner {} }
    public static void main(String[] a) {
        Class<?> c = dev.weposs.wepac.record.RecordManager.Status.class;
        System.out.println("getSimpleName() = " + c.getSimpleName());
        System.out.println("getName()       = " + c.getName());
        System.out.println("OLD filter getSimpleName().startsWith(\"RecordManager\") = "
                + c.getSimpleName().startsWith("RecordManager") + "  <-- BROKEN if false");
        System.out.println("NEW filter getName().contains(\"RecordManager\")        = "
                + c.getName().contains("RecordManager") + "  <-- correct");
    }
}
