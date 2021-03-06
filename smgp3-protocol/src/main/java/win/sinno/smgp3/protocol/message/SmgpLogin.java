package win.sinno.smgp3.protocol.message;

import win.sinno.smgp3.protocol.body.SmgpLoginBody;
import win.sinno.smgp3.protocol.header.SmgpHeader;

/**
 * smgp Login message
 *
 * @author : admin@chenlizhong.cn
 * @version : 1.0
 * @since : 2017/2/9 下午4:02
 */
public class SmgpLogin extends SmgpMessage<SmgpHeader, SmgpLoginBody> {


    private String spPwd;

    public SmgpLogin() {

    }

    public SmgpLogin(SmgpHeader smgpHeader, SmgpLoginBody smgpLoginBody) {
        super(smgpHeader, smgpLoginBody);
    }


    public String getSpPwd() {
        return spPwd;
    }

    public void setSpPwd(String spPwd) {
        this.spPwd = spPwd;
    }

}
