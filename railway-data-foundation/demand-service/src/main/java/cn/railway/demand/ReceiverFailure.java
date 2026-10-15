package cn.railway.demand;
public class ReceiverFailure extends RuntimeException {
    final int status;
    public ReceiverFailure(int status,String message){super(message);this.status=status;}
}
