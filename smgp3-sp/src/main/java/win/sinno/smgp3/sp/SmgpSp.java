package win.sinno.smgp3.sp;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import org.slf4j.Logger;
import win.sinno.smgp3.common.config.LoggerConfigs;
import win.sinno.smgp3.common.util.SequenceIdGenerator;
import win.sinno.smgp3.common.util.SmgpHeaderUtil;
import win.sinno.smgp3.communication.ISmgpCommunication;
import win.sinno.smgp3.communication.ISmgpSequence;
import win.sinno.smgp3.communication.decoder.SmgpDeliverDecoder;
import win.sinno.smgp3.communication.decoder.SmgpHeaderDecoder;
import win.sinno.smgp3.communication.decoder.SmgpLoginRespDecoder;
import win.sinno.smgp3.communication.decoder.SmgpSubmitRespDecoder;
import win.sinno.smgp3.communication.encoder.SmgpDeliverRespEncoder;
import win.sinno.smgp3.communication.encoder.SmgpHeaderEncoder;
import win.sinno.smgp3.communication.encoder.SmgpLoginEncoder;
import win.sinno.smgp3.communication.encoder.SmgpSubmitEncoder;
import win.sinno.smgp3.communication.factory.SmgpActiveTestFactory;
import win.sinno.smgp3.communication.factory.SmgpActiveTestRespFactory;
import win.sinno.smgp3.communication.factory.SmgpDeliverRespFactory;
import win.sinno.smgp3.communication.factory.SmgpLoginFactory;
import win.sinno.smgp3.protocol.body.SmgpDeliverBody;
import win.sinno.smgp3.protocol.body.SmgpLoginRespBody;
import win.sinno.smgp3.protocol.constant.SmgpRequestEnum;
import win.sinno.smgp3.protocol.constant.SmgpStatusEnum;
import win.sinno.smgp3.protocol.header.SmgpHeader;
import win.sinno.smgp3.protocol.message.*;
import win.sinno.smgp3.protocol.model.SmgpReplyMessage;
import win.sinno.smgp3.protocol.model.SmgpReportMessage;
import win.sinno.smgp3.sp.handler.ISmgpReplyHandler;
import win.sinno.smgp3.sp.handler.ISmgpReportHandler;
import win.sinno.smgp3.sp.handler.ISmgpSubmitRespHandler;
import win.sinno.smgp3.sp.netty.SmgpByte2MessageDecoder;
import win.sinno.smgp3.sp.netty.SmgpMessageHandler;

import java.util.ArrayList;
import java.util.List;

/**
 * smgp sp client
 *
 * @author : admin@chenlizhong.cn
 * @version : 1.0
 * @since : 2017/2/13 上午11:15
 */
public class SmgpSp implements ISmgpCommunication, ISmgpSequence, Runnable {

    private static final Logger LOG = LoggerConfigs.SMGP3_LOG;

    private String name;
    private String host;
    private int port;
    private String spId;
    private String spPwd;
    private String spSrcTermId;

    private ChannelFuture channelFuture;

    private volatile boolean connectFlag;

    private long lastConnTs;

    private long lastRespTs;

    private long lastActiveTestTs;

    private volatile boolean isRun = true;

    //submit resp handler
    private List<ISmgpSubmitRespHandler> submitRespHandlers = new ArrayList<ISmgpSubmitRespHandler>();

    //report handler
    private List<ISmgpReportHandler> reportHandlers = new ArrayList<ISmgpReportHandler>();

    //reply handler
    private List<ISmgpReplyHandler> replyHandlers = new ArrayList<ISmgpReplyHandler>();

    //send try count
    public static final int SEND_TRY_COUNT = 3;

    private static final long ACTIVE_TEST_TS = 30000l;

    private static final long ACTIVE_TEST_DEAD_TS = 180000;

    private SequenceIdGenerator sequenceIdGenerator;


    public SmgpSp(String name, String host, int port, String spId, String spPwd, String spSrcTermId) {
        this.name = name;
        this.host = host;
        this.port = port;
        this.spId = spId;
        this.spPwd = spPwd;
        this.spSrcTermId = spSrcTermId;

        this.sequenceIdGenerator = SequenceIdGenerator.getInstance(name);
    }

    /**
     * When an object implementing interface <code>Runnable</code> is used
     * to create a thread, starting the thread causes the object's
     * <code>run</code> method to be called in that separately executing
     * thread.
     * <p>
     * The general contract of the method <code>run</code> is that it may
     * take any action whatsoever.
     *
     * @see Thread#run()
     */
    @Override
    public void run() {
        try {
            connect();
        } catch (InterruptedException e) {
            LOG.info(e.getMessage(), e);
        }
    }

    public synchronized void connect() throws InterruptedException {


        final LengthFieldBasedFrameDecoder lengthFieldBasedFrameDecoder = new LengthFieldBasedFrameDecoder(1024, 0, 4, -4, 0);

        final SmgpByte2MessageDecoder smgpByte2MessageDecoder = new SmgpByte2MessageDecoder();

        final SmgpMessageHandler smgpMessageHandler = new SmgpMessageHandler(this);

        EventLoopGroup eventLoopGroup = new NioEventLoopGroup();

        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(eventLoopGroup);
        bootstrap.channel(NioSocketChannel.class);
        bootstrap.option(ChannelOption.SO_KEEPALIVE, true);
        bootstrap.handler(new ChannelInitializer<SocketChannel>() {

            @Override
            protected void initChannel(SocketChannel ch) throws Exception {

                ch.pipeline().addFirst(lengthFieldBasedFrameDecoder)
                        .addLast(smgpByte2MessageDecoder)
                        .addLast(smgpMessageHandler);
            }
        });

        Thread activeTestThread = new Thread() {

            @Override
            public void run() {

                while (isRun) {
                    if (connectFlag) {
                        //心跳时间步骤
                        long now = System.currentTimeMillis();
                        long activeTsStep = now - lastActiveTestTs;

                        if (activeTsStep > ACTIVE_TEST_DEAD_TS
                                && (now - lastConnTs) > ACTIVE_TEST_DEAD_TS) {

                            close();

                            reconnect();
                        } else if (activeTsStep > ACTIVE_TEST_TS) {


                            sendActiveTest(SmgpActiveTestFactory.builder(nextSeqId()).build());

                            try {
                                Thread.sleep(30000l);
                            } catch (InterruptedException e) {
                                LOG.error(e.getMessage(), e);
                                break;
                            }
                        } else {
                            try {
                                Thread.sleep(2000l);
                            } catch (InterruptedException e) {
                                LOG.error(e.getMessage(), e);
                                break;
                            }
                        }
                    } else {
                        try {
                            Thread.sleep(5000l);
                        } catch (InterruptedException e) {
                            LOG.error(e.getMessage(), e);
                            break;
                        }
                    }
                }

            }
        };

        try {

            channelFuture = bootstrap.connect(host, port).sync();

            sendConnectReq();

            activeTestThread.start();

            channelFuture.channel().closeFuture().sync();
        } finally {

            LOG.info("sp shutdown host:{},port:{}...", new Object[]{host, port});

            eventLoopGroup.shutdownGracefully();

            activeTestThread.interrupt();
        }


    }

    //是否连接
    private boolean isConnect() {
        return channelFuture != null
                && channelFuture.channel() != null
                && channelFuture.channel().isOpen();
    }

    private synchronized void sendConnectReq() {
        SmgpLogin smgpLogin = SmgpLoginFactory.builder(nextSeqId())
                .spId(spId)
                .spPwd(spPwd)
                .build();

        sendLogin(smgpLogin);
    }

    public synchronized void reconnect() {
        try {
            if (!isConnect()) {
                this.connectFlag = false;
                connect();
            }
        } catch (InterruptedException e) {
            LOG.error(e.getMessage(), e);
        }
    }

    /**
     * send smgp message
     * <p>
     * if fail default try 3 send
     *
     * @param message
     */
    @Override
    public void send(byte[] message) {

        send(message, 0);
    }

    /**
     * send smgp message, if send fail try count
     *
     * @param message
     * @param trycount
     */
    @Override
    public void send(byte[] message, int trycount) {
        try {

            if (trycount >= SEND_TRY_COUNT) {
                LOG.warn("try count >max try count,send fail:{}", message);
                return;
            }

            if (isConnect()) {
                LOG.info("sp:{} send:{}", new Object[]{name, message});

                ByteBuf byteBuf = Unpooled.wrappedBuffer(message);

                channelFuture.channel().writeAndFlush(byteBuf);
            } else {
                LOG.info("unconnect.can not send..");

                connect();

                send(message, ++trycount);
            }
        } catch (InterruptedException e) {
            LOG.error(e.getMessage(), e);
        }
    }

    /**
     * receive smgp message
     *
     * @param message
     * @return
     */
    @Override
    public void receive(byte[] message) {
        handlerMessage(message);
    }

    /**
     * send smgp Login message
     *
     * @param smgpLogin
     */
    @Override
    public void sendLogin(SmgpLogin smgpLogin) {
        send(SmgpLoginEncoder.getInstance().encode(smgpLogin));
    }

    /**
     * send smgp ActiveTest message
     *
     * @param smgpActiveTest
     */
    @Override
    public void sendActiveTest(SmgpActiveTest smgpActiveTest) {
        send(SmgpHeaderEncoder.getInstance().encode(smgpActiveTest.getHeader()));
    }

    /**
     * send smgp ActiveTestResp message
     *
     * @param smgpActiveTestResp
     */
    @Override
    public void sendActiveTestResp(SmgpActiveTestResp smgpActiveTestResp) {
        send(SmgpHeaderEncoder.getInstance().encode(smgpActiveTestResp.getHeader()));
    }

    /**
     * send smgp Submit message
     *
     * @param smgpSubmit
     */
    @Override
    public void sendSubmit(SmgpSubmit smgpSubmit) {
        send(SmgpSubmitEncoder.getInstance().encode(smgpSubmit));
    }

    public void sendSubmit(Integer sign, List<SmgpSubmit> smgpSubmits) {
        synchronized (sign) {
            for (SmgpSubmit smgpSubmit : smgpSubmits) {
                sendSubmit(smgpSubmit);
            }
        }
    }

    /**
     * send smgp DeliverResp message
     *
     * @param smgpDeliverResp
     */
    @Override
    public void sendDeliverResp(SmgpDeliverResp smgpDeliverResp) {
        send(SmgpDeliverRespEncoder.getInstance().encode(smgpDeliverResp));
    }

    /**
     * receive -> hander
     *
     * @param bytes
     */
    @Override
    public void handlerMessage(byte[] bytes) {
        int requestId = SmgpHeaderUtil.getMessageRequestId(bytes);

        LOG.info("receive smgp message:{}", bytes);
        SmgpRequestEnum smgpRequestEnum = SmgpRequestEnum.getById(requestId);

        switch (smgpRequestEnum) {

            case LOGIN_RESP:

                handlerLoginResp(SmgpLoginRespDecoder.getInstance().decode(bytes));
                break;

            case SUBMIT_RESP:

                handlerSubmitResp(SmgpSubmitRespDecoder.getInstance().decode(bytes));
                break;

            case DELIVER:

                handlerDeliver(SmgpDeliverDecoder.getInstance().decode(bytes));
                break;

            case ACTIVE_TEST_RESP:

                handlerActiveTestResp(new SmgpActiveTestResp(SmgpHeaderDecoder.getInstance().decode(bytes)));
                break;

            case ACTIVE_TEST:

                handlerActiveTest(new SmgpActiveTest(SmgpHeaderDecoder.getInstance().decode(bytes)));
                break;

            default:
                LOG.error("unkonw can not handler. smgp message:{}", bytes);
        }

    }

    /**
     * handler smgp LoginResp message
     *
     * @param smgpLoginResp
     */
    @Override
    public void handlerLoginResp(SmgpLoginResp smgpLoginResp) {
        SmgpLoginRespBody body = smgpLoginResp.getBody();
        int status = body.getStatus();

        SmgpStatusEnum smgpStatusEnum = SmgpStatusEnum.getById(status);

        switch (smgpStatusEnum) {

            case SUCCESS:
                connectFlag = true;
                lastConnTs = System.currentTimeMillis();
                LOG.info("login success");

                break;

            default:
                LOG.warn("login fail status:{}", status);
        }

        LOG.info("login resp status:{}", status);
    }

    /**
     * handler smgp ActiveTest message
     *
     * @param smgpActiveTest
     */
    @Override
    public void handlerActiveTest(SmgpActiveTest smgpActiveTest) {
        SmgpHeader header = smgpActiveTest.getHeader();

        SmgpActiveTestResp activeTestResp = SmgpActiveTestRespFactory.builder(nextSeqId())
                .build();

        send(SmgpHeaderEncoder.getInstance().encode(activeTestResp.getHeader()));
    }

    /**
     * handler smgp ActiveTestResp message
     *
     * @param smgpActiveTestResp
     */
    @Override
    public void handlerActiveTestResp(SmgpActiveTestResp smgpActiveTestResp) {
        lastActiveTestTs = System.currentTimeMillis();
    }

    /**
     * handler smgp SubmitResp message
     *
     * @param smgpSubmitResp
     */
    @Override
    public void handlerSubmitResp(SmgpSubmitResp smgpSubmitResp) {
        LOG.info("get submit resp :{}", smgpSubmitResp);
        // add SmgpSubmitResp message handler

        for (ISmgpSubmitRespHandler submitRespHandler : submitRespHandlers) {
            submitRespHandler.handler(smgpSubmitResp);
        }
    }

    /**
     * handler smgp Deliver message
     *
     * @param smgpDeliver
     */
    @Override
    public void handlerDeliver(SmgpDeliver smgpDeliver) {

        LOG.info("get smgp deliver:{}", smgpDeliver);

        // add SmgpDeliver message hanlder

        if (1 == smgpDeliver.getBody().getIsReport()) {
            handlerReport(smgpDeliver);
        } else {
            handlerReply(smgpDeliver);
        }

        //report，reply 消息确认
        ackDeliverMsg(smgpDeliver);

    }

    /**
     * @param smgpDeliver
     */
    private void handlerReport(SmgpDeliver smgpDeliver) {

        SmgpReportMessage reportMessage = smgpDeliver.getSmgpReportMessage();

        for (ISmgpReportHandler reportHandler : reportHandlers) {
            reportHandler.handler(reportMessage);
        }

    }

    private void handlerReply(SmgpDeliver smgpDeliver) {
        SmgpDeliverBody body = smgpDeliver.getBody();

        SmgpReplyMessage replyMessage = new SmgpReplyMessage(
                body.getMsgId(),
                body.getRecvTime(),
                body.getSrcTermId(),
                body.getDestTermId(),
                body.getMsgContent()
        );

        for (ISmgpReplyHandler replyHandler : replyHandlers) {
            replyHandler.handler(replyMessage);
        }
    }

    /**
     * ack deliver msg
     *
     * @param smgpDeliver
     */
    private void ackDeliverMsg(SmgpDeliver smgpDeliver) {
        int sequenceId = smgpDeliver.getHeader().getSequenceId();
        String msgId = smgpDeliver.getBody().getMsgId();

        SmgpDeliverResp smgpDeliverResp = SmgpDeliverRespFactory.builder(sequenceId)
                .msgId(msgId)
                .status(SmgpStatusEnum.SUCCESS.getId())
                .build();

        sendDeliverResp(smgpDeliverResp);
    }

    /**
     * add smgp submit resp handler
     *
     * @param smgpSubmitRespHandler
     */
    public SmgpSp addSmgpSubmitRespHandler(ISmgpSubmitRespHandler smgpSubmitRespHandler) {
        submitRespHandlers.add(smgpSubmitRespHandler);
        return this;
    }

    /**
     * add smgp report handler
     *
     * @param smgpReportHandler
     */
    public SmgpSp addSmgpReportHandler(ISmgpReportHandler smgpReportHandler) {
        reportHandlers.add(smgpReportHandler);
        return this;
    }

    /**
     * add smgp reply handler
     *
     * @param smgpReplyHandler
     */
    public SmgpSp addSmgpReplyHandler(ISmgpReplyHandler smgpReplyHandler) {
        replyHandlers.add(smgpReplyHandler);
        return this;
    }


    public void close() {
        LOG.info("sp:{} close now ", name);

        if (channelFuture != null
                && channelFuture.channel() != null
                && channelFuture.channel().isOpen()) {
            channelFuture.channel().close();
        }

        this.connectFlag = false;
    }

    public void stop() {
        isRun = false;
        close();
    }

    public String getName() {
        return name;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public String getSpId() {
        return spId;
    }

    public String getSpPwd() {
        return spPwd;
    }

    public String getSpSrcTermId() {
        return spSrcTermId;
    }

    public boolean isConnectFlag() {
        return connectFlag;
    }


    @Override
    public String toString() {
        return "SmgpSp{" +
                "name='" + name + '\'' +
                ", host='" + host + '\'' +
                ", port=" + port +
                ", spId='" + spId + '\'' +
                ", spPwd='" + spPwd + '\'' +
                ", spSrcTermId='" + spSrcTermId + '\'' +
                ", channelFuture=" + channelFuture +
                ", connectFlag=" + connectFlag +
                ", lastConnTs=" + lastConnTs +
                ", lastRespTs=" + lastRespTs +
                ", lastActiveTestTs=" + lastActiveTestTs +
                ", submitRespHandlers=" + submitRespHandlers +
                ", reportHandlers=" + reportHandlers +
                ", replyHandlers=" + replyHandlers +
                '}';
    }

    @Override
    public Integer nextSeqId() {
        return sequenceIdGenerator.nextSeqId();
    }
}
