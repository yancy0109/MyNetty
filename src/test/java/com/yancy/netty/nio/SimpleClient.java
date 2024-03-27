package com.yancy.netty.nio;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.Iterator;

/**
 * @author yancy0109
 * @date 2024/3/26
 */
public class SimpleClient {

    private static final Logger logger = LoggerFactory.getLogger(SimpleClient.class);

    private static volatile boolean close = false;


    public static void main(String[] args) {
        try {
            // 获取客户端Channel
            SocketChannel socketChannel = SocketChannel.open();
            // 设定为非阻塞
            socketChannel.configureBlocking(false);
            Selector selector = Selector.open();
            // Register ShutdownHook
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    logger.info("Client is closing.");
                    if (socketChannel.isOpen()) {
                        socketChannel.close();
                    }
                    close = true;
                    selector.wakeup();  // Wake up Selector(By write bytes to make select method return).
                    selector.close();
                } catch (IOException e) {
                    logger.info("Error.=", e);
                }
            }));
            // 注册Channel
            socketChannel.register(selector, SelectionKey.OP_CONNECT); // Register ConnectionEvent
            socketChannel.connect(new InetSocketAddress( 8080));
            // 分配字节缓冲需区用于接收服务端数据
            ByteBuffer readBuffer = ByteBuffer.allocate(1024);
            // 轮询事件 isOpen && !keys.isEmpty(Maybe could be ignored, just shutdown by ShutdownHook)
            while (selector.isOpen() && !selector.keys().isEmpty()) {
                // Select Event, if nil, blocking.
                try {
                    selector.select();
                } catch (ClosedSelectorException e) {
                    continue;
                }
                // Check Selector is Open. If Selector is wake up by ShutdownHook(selector#wakeup), then break.
                if (close || !selector.isOpen()) {
                    break;
                }
                // 遍历获取事件
                Iterator<SelectionKey> selectionKeyIterator = selector.selectedKeys().iterator();
                while (selectionKeyIterator.hasNext()) {
                    SelectionKey key = selectionKeyIterator.next();
                    // Handle Connectable Event
                    if (key.isValid() && key.isConnectable()) {
                        // FinishConnect
                        boolean finishedConnect;
                        try {
                            finishedConnect = socketChannel.finishConnect();
                        } catch (ConnectException e) {
                            logger.error("Client Connect Failed.", e);
                            selectionKeyIterator.remove();  // Handle next SelectionKey.
                            key.cancel();   // Cancel SelectionKey, Cause Connect Failed.
                            key.channel().close();
                            selector.wakeup();  // wakeup for remove this key.
                            continue;
                        }
                        // 成功 -> 注册 READABLE EVENT
                        // else: 等待再次轮询
                        if (finishedConnect) {
                            logger.info("Client has connected. Register Readable Event to Selector.");
                            key.interestOps(SelectionKey.OP_READ); // Register Readable Event.
                            // Send back Msg to Server.
                            ByteBuffer sendBuffer = ByteBuffer.wrap("Hello Server, this is client.".getBytes());
                            while (sendBuffer.hasRemaining()) {
                                socketChannel.write(sendBuffer);
                            }
                        }
                    }
                    // Handle Readable Event
                    if (key.isValid() && key.isReadable()) {
                        SocketChannel channel = (SocketChannel) key.channel();
                        int len = channel.read(readBuffer);
                        if (len > 0) {
                            readBuffer.flip(); // ByteBuffer Changed to Read Mode.
                            byte[] bytes = new byte[readBuffer.limit()];
                            readBuffer.get(bytes);
                            logger.info("Client Received: {}", new String(bytes));
                            readBuffer.clear(); // ByteBuffer Changed to Write Mode.
                        } else if (len == -1) {
                            logger.info("Server has closed.");
                            key.cancel();
                            selector.wakeup();
                            channel.close();
                            continue;
                        }
                    }
                    selectionKeyIterator.remove();  // Remove this SelectKey(handled).
                }
            }
        } catch (IOException e) {
            logger.info("IOException, ", e);
        }
    }

}
