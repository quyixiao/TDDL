package com.alibaba.cobar.net;

import java.io.EOFException;
import java.io.IOException;
import java.io.PipedOutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import com.alibaba.cobar.ErrorCode;
import com.alibaba.cobar.net.buffer.BufferPool;
import com.alibaba.cobar.net.buffer.BufferQueue;
import com.alibaba.cobar.net.util.TimeUtil;

/**
 * @author xianmao.hexm
 */
public abstract class AbstractConnection implements NIOConnection {

    private static final int      OP_NOT_READ      = ~SelectionKey.OP_READ;
    private static final int      OP_NOT_WRITE     = ~SelectionKey.OP_WRITE;

    protected final SocketChannel channel;
    protected NIOProcessor        processor;
    protected SelectionKey        processKey;
    protected final ReentrantLock keyLock;
    protected int                 packetHeaderSize;
    protected int                 maxPacketSize;
    protected int                 readBufferOffset;
    protected ByteBuffer          readBuffer;
    protected BufferQueue         writeQueue;
    protected final ReentrantLock writeLock;
    protected boolean             isRegistered;
    protected final AtomicBoolean isClosed;
    protected boolean             isSocketClosed;
    protected long                startupTime;
    protected long                lastReadTime;
    protected long                lastWriteTime;
    protected long                netInBytes;
    protected long                netOutBytes;
    protected int                 writeAttempts;
    protected PipedOutputStream   outPutPipeStream;
    protected boolean             canReadNewPacket = true;
    protected byte                packetId         = 0;

    public AbstractConnection(SocketChannel channel){
        this.channel = channel;
        this.keyLock = new ReentrantLock();
        this.writeLock = new ReentrantLock();
        this.isClosed = new AtomicBoolean(false);
        this.startupTime = TimeUtil.currentTimeMillis();
        this.lastReadTime = startupTime;
        this.lastWriteTime = startupTime;
    }

    public SocketChannel getChannel() {
        return channel;
    }

    public int getPacketHeaderSize() {
        return packetHeaderSize;
    }

    public void setPacketHeaderSize(int packetHeaderSize) {
        this.packetHeaderSize = packetHeaderSize;
    }

    public int getMaxPacketSize() {
        return maxPacketSize;
    }

    public void setMaxPacketSize(int maxPacketSize) {
        this.maxPacketSize = maxPacketSize;
    }

    public long getStartupTime() {
        return startupTime;
    }

    public long getLastReadTime() {
        return lastReadTime;
    }

    public long getLastWriteTime() {
        return lastWriteTime;
    }

    public long getNetInBytes() {
        return netInBytes;
    }

    public long getNetOutBytes() {
        return netOutBytes;
    }

    public int getWriteAttempts() {
        return writeAttempts;
    }

    public NIOProcessor getProcessor() {
        return processor;
    }

    public ByteBuffer getReadBuffer() {
        return readBuffer;
    }

    public BufferQueue getWriteQueue() {
        return writeQueue;
    }

    public void setWriteQueue(BufferQueue writeQueue) {
        this.writeQueue = writeQueue;
    }

    /**
     * ????????????
     */
    public ByteBuffer allocate() {
        return processor.getBufferPool().allocate();
    }

    /**
     * ????????????
     */
    public void recycle(ByteBuffer buffer) {
        processor.getBufferPool().recycle(buffer);
    }

    @Override
    public void register(Selector selector) throws IOException {
        try {
            processKey = channel.register(selector, SelectionKey.OP_READ, this);
            isRegistered = true;
        } finally {
            if (isClosed.get()) {
                clearSelectionKey();
            }
        }
    }

    @Override
    public void read() throws IOException {
        if (!this.canReadNewPacket) {
            return;
        }

        ByteBuffer buffer = this.readBuffer;
        int got = channel.read(buffer);
        lastReadTime = TimeUtil.currentTimeMillis();
        //fix read bug <= 0
        if (got < 0) {
            this.close();
            return;
        }else if(got == 0){
            if(!this.channel.isOpen()){
                this.close();
                return;
            }
        }
        netInBytes += got;
        processor.addNetInBytes(got);

        // ????????????
        int offset = readBufferOffset, length = 0, position = buffer.position();
        for (;;) {
            length = getPacketLength(buffer, offset);
            if (length == -1) {// ??????????????????????????????????????????
                if (!buffer.hasRemaining()) {
                    checkReadBuffer(buffer, offset, position);
                }
                break;
            }
            if (position >= offset + length) {
                // ??????????????????????????????????????????
                buffer.position(offset);
                byte[] data = new byte[length];

                buffer.get(data, 0, length);

                handleData(data);

                // ???????????????
                offset += length;
                if (position == offset) {// ??????????????????????????????
                    if (readBufferOffset != 0) {
                        readBufferOffset = 0;
                    }
                    buffer.clear();
                    break;
                } else {// ???????????????????????????
                    readBufferOffset = offset;
                    buffer.position(position);
                    continue;
                }
            } else {// ?????????????????????????????????
                if (!buffer.hasRemaining()) {
                    checkReadBuffer(buffer, offset, position);
                }
                break;
            }
        }
    }

    public void write(byte[] data) {
        ByteBuffer buffer = allocate();
        buffer = writeToBuffer(data, buffer);
        write(buffer);
    }

    @Override
    public void write(ByteBuffer buffer) {
        if (isClosed.get()) {
            processor.getBufferPool().recycle(buffer);
            return;
        }
        if (isRegistered) {
            try {
                writeQueue.put(buffer);
            } catch (InterruptedException e) {
                handleError(ErrorCode.ERR_PUT_WRITE_QUEUE, e);
                return;
            }
            processor.postWrite(this);
        } else {
            processor.getBufferPool().recycle(buffer);
            close();
        }
    }

    @Override
    public void writeByQueue() throws IOException {
        if (isClosed.get()) {
            return;
        }
        final ReentrantLock lock = this.writeLock;
        lock.lock();
        try {
            // ??????????????????????????????????????????????????????????????????
            // 1.??????key???????????????????????????
            // 2.write0()??????false???
            if ((processKey.interestOps() & SelectionKey.OP_WRITE) == 0 && !write0()) {
                enableWrite();
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void writeByEvent() throws IOException {
        if (isClosed.get()) {
            return;
        }
        final ReentrantLock lock = this.writeLock;
        lock.lock();
        try {
            // ??????????????????????????????????????????????????????????????????
            // 1.write0()??????true???
            // 2.???????????????buffer?????????
            if (write0() && writeQueue.size() == 0) {
                disableWrite();
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * ???????????????
     */
    public void enableRead() {
        final Lock lock = this.keyLock;
        lock.lock();
        try {
            SelectionKey key = this.processKey;
            key.interestOps(key.interestOps() | SelectionKey.OP_READ);
        } finally {
            lock.unlock();
        }
        processKey.selector().wakeup();
    }

    /**
     * ???????????????
     */
    public void disableRead() {
        final Lock lock = this.keyLock;
        lock.lock();
        try {
            SelectionKey key = this.processKey;
            key.interestOps(key.interestOps() & OP_NOT_READ);
        } finally {
            lock.unlock();
        }
    }

    /**
     * ??????WriteBuffer??????????????????????????????????????????????????????????????????
     */
    public ByteBuffer checkWriteBuffer(ByteBuffer buffer, int capacity) {
        if (capacity > buffer.remaining()) {
            write(buffer);
            return processor.getBufferPool().allocate();
        } else {
            return buffer;
        }
    }

    /**
     * ?????????????????????????????????????????????????????????????????????????????????????????????
     */
    public ByteBuffer writeToBuffer(byte[] src, ByteBuffer buffer) {
        int offset = 0;
        int length = src.length;
        int remaining = buffer.remaining();
        while (length > 0) {
            if (remaining >= length) {
                buffer.put(src, offset, length);
                break;
            } else {
                buffer.put(src, offset, remaining);
                write(buffer);
                buffer = processor.getBufferPool().allocate();
                offset += remaining;
                length -= remaining;
                remaining = buffer.remaining();
                continue;
            }
        }
        return buffer;
    }

    @Override
    public boolean close() {
        if (isClosed.get()) {
            return false;
        } else {
            if (closeSocket()) {
                return isClosed.compareAndSet(false, true);
            } else {
                return false;
            }
        }
    }

    public boolean isClosed() {
        return isClosed.get();
    }

    /**
     * ???Processor?????????????????????
     */
    protected abstract void idleCheck();

    /**
     * ??????????????????
     */
    protected void cleanup() {
        BufferPool pool = processor.getBufferPool();
        ByteBuffer buffer = null;

        // ??????????????????
        buffer = this.readBuffer;
        if (buffer != null) {
            this.readBuffer = null;
            pool.recycle(buffer);
        }

        // ??????????????????
        while ((buffer = writeQueue.poll()) != null) {
            pool.recycle(buffer);
        }
    }

    /**
     * ?????????????????????????????????MySQL?????????????????????????????????????????????
     */
    protected int getPacketLength(ByteBuffer buffer, int offset) {
        if (buffer.position() < offset + packetHeaderSize) {
            return -1;
        } else {
            int length = buffer.get(offset) & 0xff;
            length |= (buffer.get(++offset) & 0xff) << 8;
            length |= (buffer.get(++offset) & 0xff) << 16;
            return length + packetHeaderSize;
        }
    }

    /**
     * ??????ReadBuffer?????????????????????????????????????????????????????????
     */
    private ByteBuffer checkReadBuffer(ByteBuffer buffer, int offset, int position) {
        // ???????????????0???????????????????????????????????????????????????0????????????
        if (offset == 0) {
            if (buffer.capacity() >= maxPacketSize) {
                throw new IllegalArgumentException("Packet size over the limit.");
            }
            int size = buffer.capacity() << 1;
            size = (size > maxPacketSize) ? maxPacketSize : size;
            ByteBuffer newBuffer = ByteBuffer.allocate(size);
            buffer.position(offset);
            newBuffer.put(buffer);
            readBuffer = newBuffer;
            // ???????????????????????????
            processor.getBufferPool().recycle(buffer);
            return newBuffer;
        } else {
            buffer.position(offset);
            buffer.compact();
            readBufferOffset = 0;
            return buffer;
        }
    }

    private boolean write0() throws IOException {
        // ????????????????????????????????????
        ByteBuffer buffer = writeQueue.attachment();
        if (buffer != null) {
            int written = channel.write(buffer);
            if (written > 0) {
                netOutBytes += written;
                processor.addNetOutBytes(written);
            }
            lastWriteTime = TimeUtil.currentTimeMillis();
            if (buffer.hasRemaining()) {
                writeAttempts++;
                return false;
            } else {
                writeQueue.attach(null);
                processor.getBufferPool().recycle(buffer);
            }
        }
        // ?????????????????????????????????
        if ((buffer = writeQueue.poll()) != null) {
            // ??????????????????????????????buffer???????????????????????????
            if (buffer.position() == 0) {
                processor.getBufferPool().recycle(buffer);
                close();
                return true;
            }
            buffer.flip();
            int written = channel.write(buffer);
            if (written > 0) {
                netOutBytes += written;
                processor.addNetOutBytes(written);
            }
            lastWriteTime = TimeUtil.currentTimeMillis();
            if (buffer.hasRemaining()) {
                writeQueue.attach(buffer);
                writeAttempts++;
                return false;
            } else {
                processor.getBufferPool().recycle(buffer);
            }
        }
        return true;
    }

    /**
     * ???????????????
     */
    private void enableWrite() {
        final Lock lock = this.keyLock;
        lock.lock();
        try {
            SelectionKey key = this.processKey;
            key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
        } finally {
            lock.unlock();
        }
        processKey.selector().wakeup();
    }

    /**
     * ???????????????
     */
    private void disableWrite() {
        final Lock lock = this.keyLock;
        lock.lock();
        try {
            SelectionKey key = this.processKey;
            key.interestOps(key.interestOps() & OP_NOT_WRITE);
        } finally {
            lock.unlock();
        }
    }

    private void clearSelectionKey() {
        final Lock lock = this.keyLock;
        lock.lock();
        try {
            SelectionKey key = this.processKey;
            if (key != null && key.isValid()) {
                key.attach(null);
                key.cancel();
            }
        } finally {
            lock.unlock();
        }
    }

    private boolean closeSocket() {
        clearSelectionKey();
        SocketChannel channel = this.channel;
        if (channel != null) {
            boolean isSocketClosed = true;
            Socket socket = channel.socket();
            if (socket != null) {
                try {
                    socket.close();
                } catch (Throwable e) {
                }
                isSocketClosed = socket.isClosed();
            }
            try {
                channel.close();
            } catch (Throwable e) {
            }
            return isSocketClosed && (!channel.isOpen());
        } else {
            return true;
        }
    }

}
