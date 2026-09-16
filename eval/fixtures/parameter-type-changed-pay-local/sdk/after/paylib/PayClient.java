package paylib;

public class PayClient {

    public String charge(Ref ref) {
        return "charged:" + ref.key();
    }

    public String status() {
        return "up";
    }

}
