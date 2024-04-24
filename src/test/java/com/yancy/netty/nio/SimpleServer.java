package com.yancy.netty.nio;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.*;

/**
 * @author yancy0109
 * @date 2024/3/27
 */
public class SimpleServer {

    public static final Logger LOGGER = LoggerFactory.getLogger(SimpleServer.class);
    private static volatile boolean close = false;

    public static void main(String[] args) {
        try {
            // Create Server Channel
            ServerSocketChannel serverSocketChannel = ServerSocketChannel.open();
            // Set For NIO
            serverSocketChannel.configureBlocking(false);
            // Create Selector
            final Selector selector = Selector.open(); // Register selector to ShutdownHook Over line 30.;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                if (selector != null && selector.isOpen()) {
                    close = true;
                    selector.wakeup();
                    for (SelectionKey key : selector.keys()) {
                        try {
                            SelectableChannel channel = key.channel();
                            if (channel.isOpen()) {
                                LOGGER.info("ShutHook close. key: {}", key.isValid() ? key.interestOps() : "Canceled");
                                channel.close();
                            }
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    }

                    try {
                        selector.close();
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }
            }));
            serverSocketChannel.bind(new InetSocketAddress(8080));  // Bind to port 8080.
             // Register server to selector, Bind with Accept.
            try {
                serverSocketChannel.register(selector, SelectionKey.OP_ACCEPT);
            } catch (ClosedChannelException e) {
                return;
            }

            Work work = new Work();

            // 分配字节缓冲需区用于接收客户端数据
            ByteBuffer readBuffer = ByteBuffer.allocate(1024);
            // 轮询Selector, select for Event
            while (selector.isOpen() && !selector.keys().isEmpty()) {
                try {
                    selector.select();
                } catch (ClosedSelectorException e) {
                    continue;
                }
                // Check Selector is Open. If Selector is wake up by ShutdownHook(selector#wakeup), then break.
                if (close || !selector.isOpen()) {
                    break;
                }
                Iterator<SelectionKey> iterator = selector.selectedKeys().iterator();
                while (iterator.hasNext()) {
                    SelectionKey selectionKey = iterator.next();
                    iterator.remove();  // Remove SelectionKey will be handled.
                    if (!selectionKey.isValid()) {
                        continue;
                    }
                    if (selectionKey.isAcceptable()) {
                        // Accept Event
                        ServerSocketChannel server = (ServerSocketChannel) selectionKey.channel();
                        SocketChannel socketChannel;
                        try {
                            socketChannel = server.accept();
                        } catch (IOException e) {
                            LOGGER.error("Server has error when connect with client.", e);
                            continue;
                        }
                        work.register(socketChannel); // Register to the Work Thread.
                        LOGGER.info("Accept Connection Request From Client, from: {}", socketChannel.getRemoteAddress());
                        ByteBuffer sendBuffer = ByteBuffer.wrap("Hello Client, this is Server.".getBytes());
                        while (sendBuffer.hasRemaining()) {
                            socketChannel.write(sendBuffer);    // Send Msg to Client.
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
