package com.yancy.netty.nio;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.channels.spi.SelectorProvider;
import java.util.Iterator;

public class Work implements Runnable {

    private final Logger logger = LoggerFactory.getLogger(Work.class);

    private final Thread thread;

    private final SelectorProvider provider;

    private Selector selector;

    private volatile boolean isRunning = false;

    public Work() {
        provider = SelectorProvider.provider();
        this.thread = new Thread(this);
    }

    public void register(SocketChannel socketChannel) {
        start();
        try {
            socketChannel.configureBlocking(false);
            socketChannel.register(selector, SelectionKey.OP_READ);
            if (isRunning) {
                return;
            }
            start();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void start() {
        if (isRunning) {
            logger.info("Work Thread: {} already in working.", thread.getName());
            return;
        }
        try {
            this.selector = provider.openSelector();
            Runtime.getRuntime().addShutdownHook(new Thread(
                    () -> {
                        try {
                            this.close();
                        } catch (IOException e) {
                            logger.error("There is error when close work thread, {}", e.getMessage(), e);
                            throw new RuntimeException(e);
                        }
                    }
            ));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        isRunning = true;
        this.thread.start();
    }

    @Override
    public void run() {
        // 分配字节缓冲需区用于接收客户端数据
        ByteBuffer readBuffer = ByteBuffer.allocate(1024);
        // 轮询Selector, select for Event
        try {

            while (selector.isOpen() && !selector.keys().isEmpty()) {
                try {
                    selector.select();
                } catch (ClosedSelectorException e) {
                    continue;
                }
                // Check Selector is Open. If Selector is wake up by ShutdownHook(selector#wakeup), then break.
                if (!isRunning || !selector.isOpen()) {
                    break;
                }
                Iterator<SelectionKey> iterator = selector.selectedKeys().iterator();
                while (iterator.hasNext()) {
                    SelectionKey selectionKey = iterator.next();
                    iterator.remove();  // Remove SelectionKey will be handled.
                    if (!selectionKey.isValid()) {
                        continue;
                    }
                    if (selectionKey.isReadable()) {
                        SocketChannel channel = (SocketChannel) selectionKey.channel();
                        int len = 0;
                        try {
                            len = channel.read(readBuffer);
                        } catch (IOException e) {
                            logger.error("Server Channel has error when read from client.", e);
                            channel.close();
                            selectionKey.cancel();
                            selector.wakeup(); // Wakeup Selector for remove this key.
                        }
                        if (len > 0) {
                            readBuffer.flip(); // ByteBuffer Changed to Read Mode.
                            byte[] bytes = new byte[readBuffer.limit()];
                            readBuffer.get(bytes);
                            logger.info("Server Received: {}", new String(bytes));
                            readBuffer.clear(); // ByteBuffer Changed to Write Mode.
                        } else if (len == -1) {
                            logger.info("Client has closed.");
                            channel.close();
                            selectionKey.cancel();
                            selector.wakeup();
                        }
                    }
                    // Else Event...
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void close() throws IOException {
        if (selector != null && selector.isOpen()) {
            isRunning = true;
            selector.wakeup();
            for (SelectionKey key : selector.keys()) {
                try {
                    SelectableChannel channel = key.channel();
                    if (channel.isOpen()) {
                        logger.info("ShutHook close. key: {}", key.isValid() ? key.interestOps() : "Canceled");
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
    }
}
