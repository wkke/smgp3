package win.sinno.smgp3.communication.decoder;

import win.sinno.smgp3.protocol.ISmgpProtocol;

/**
 * smgp message 解码器
 *
 * @author : admin@chenlizhong.cn
 * @version : 1.0
 * @since : 2017/2/9 下午4:18
 */
public interface ISmgpMessageDecoder<MSG extends ISmgpProtocol> {

    /**
     * @param bytes
     * @return
     */
    MSG decode(byte[] bytes);
}
