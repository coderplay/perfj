public class OffCPU {

    public static void OnCPU() {
        long counter = 0L;
        for(int j = 0; j < 1000000; j++) {
            counter+=j;
        }


    }


    public static void main(String[] args) throws Throwable {


        for (int i = 0; i <  1000000; i++) {
            Thread.sleep(10);
            OnCPU();
        }
    }
}
