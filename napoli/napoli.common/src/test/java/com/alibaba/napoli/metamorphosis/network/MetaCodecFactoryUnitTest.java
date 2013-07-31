package com.alibaba.napoli.metamorphosis.network;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.alibaba.napoli.gecko.core.buffer.IoBuffer;
import com.alibaba.napoli.metamorphosis.exception.MetaCodecException;
import com.alibaba.napoli.metamorphosis.network.MetamorphosisWireFormatType.MetaCodecFactory;
import com.alibaba.napoli.metamorphosis.transaction.LocalTransactionId;
import com.alibaba.napoli.metamorphosis.transaction.TransactionId;
import com.alibaba.napoli.metamorphosis.transaction.TransactionInfo;
import com.alibaba.napoli.gecko.core.core.CodecFactory.Decoder;

public class MetaCodecFactoryUnitTest {
    private final MetaCodecFactory codecFactory = new MetaCodecFactory();
    final Decoder decoder = this.codecFactory.getDecoder();


    @Test
    public void testDecodeEmptyBuffer() {
        assertNull(this.decoder.decode(null, null));
        assertNull(this.decoder.decode(IoBuffer.allocate(0), null));
    }


    @Test
    public void testDecodePutCommand() {

        final PutCommand putCommand = new PutCommand("test", 1, "hello".getBytes(), null, 0, 0);
        final IoBuffer buf = putCommand.encode();

        final PutCommand decodedCmd = (PutCommand) this.decoder.decode(buf, null);
        assertNotNull(decodedCmd);
        assertEquals(putCommand, decodedCmd);
        assertFalse(buf.hasRemaining());
    }


    @Test
    public void testDecodePutCommand_HasTransactionId() {
        final TransactionId xid = new LocalTransactionId("test", 100);
        final PutCommand putCommand = new PutCommand("test", 1, "hello".getBytes(), xid, 0, 0);
        final IoBuffer buf = putCommand.encode();

        final PutCommand decodedCmd = (PutCommand) this.decoder.decode(buf, null);
        assertNotNull(decodedCmd);
        assertNotNull(decodedCmd.getTransactionId());
        assertEquals(putCommand, decodedCmd);
        assertEquals(putCommand.getTransactionId(), decodedCmd.getTransactionId());
        assertFalse(buf.hasRemaining());
    }


    @Test
    public void testDecodeSyncCommand() {
        final SyncCommand syncCmd = new SyncCommand("test", 1, "hello".getBytes(), 9999L, 0, 0);
        final IoBuffer buf = syncCmd.encode();

        final SyncCommand decodedCmd = (SyncCommand) this.decoder.decode(buf, null);
        assertNotNull(decodedCmd);
        assertEquals(9999L, decodedCmd.getMsgId());
        // assertNotNull(decodedCmd.getTransactionId());
        assertEquals(syncCmd, decodedCmd);
        assertEquals(syncCmd.getMsgId(), decodedCmd.getMsgId());
        assertFalse(buf.hasRemaining());
    }


    @Test
    public void testDecodeGetCommand() {
        final GetCommand cmd = new GetCommand("test", "boyan", 1, 1000L, 1024 * 1024, -3);
        final IoBuffer buf = cmd.encode();

        final GetCommand decodedCmd = (GetCommand) this.decoder.decode(buf, null);
        assertNotNull(decodedCmd);
        assertEquals(cmd, decodedCmd);
        assertFalse(buf.hasRemaining());
    }


    @Test
    public void testDecodeDataCommand() {

        final IoBuffer buf = IoBuffer.allocate(100);
        buf.put("value 5 99\r\nhello".getBytes());
        buf.flip();

        final DataCommand decodedCmd = (DataCommand) this.decoder.decode(buf, null);
        assertNotNull(decodedCmd);
        assertEquals((Integer) 99, decodedCmd.getOpaque());
        assertEquals("hello", new String(decodedCmd.getData()));
        assertFalse(buf.hasRemaining());
    }


    @Test
    public void testDecodeBooleanCommand() {
        final BooleanCommand cmd = new BooleanCommand(99, HttpStatus.NotFound, "not found");
        final IoBuffer buf = cmd.encode();
        final BooleanCommand decodedCmd = (BooleanCommand) this.decoder.decode(buf, null);
        assertNotNull(decodedCmd);
        assertEquals(cmd, decodedCmd);
        assertFalse(buf.hasRemaining());
    }


    @Test
    public void testDecodeVersion() {
        IoBuffer buf = IoBuffer.wrap("version\r\n".getBytes());
        VersionCommand versionCommand = (VersionCommand) this.decoder.decode(buf, null);
        assertNotNull(versionCommand);
        assertEquals(Integer.MAX_VALUE, (int) versionCommand.getOpaque());

        buf = IoBuffer.wrap("version -1\r\n".getBytes());
        versionCommand = (VersionCommand) this.decoder.decode(buf, null);
        assertNotNull(versionCommand);
        assertEquals(-1, (int) versionCommand.getOpaque());
    }


    @Test
    public void testDecodeOffsetCommand() {
        final OffsetCommand cmd = new OffsetCommand("test", "boyan", 1, 1000L, -1);
        final IoBuffer buf = cmd.encode();
        final OffsetCommand decodedCmd = (OffsetCommand) this.decoder.decode(buf, null);
        assertNotNull(decodedCmd);
        assertEquals(cmd, decodedCmd);
        assertFalse(buf.hasRemaining());
    }


    @Test(expected = MetaCodecException.class)
    public void testDecodeUnknowCommand() {
        final IoBuffer buf = IoBuffer.wrap("just for test\r\n".getBytes());
        this.decoder.decode(buf, null);
    }


    @Test
    public void testDecodeNotALine() {
        final IoBuffer buf = IoBuffer.wrap("just for test".getBytes());
        assertNull(this.decoder.decode(buf, null));
    }


    @Test
    public void decodePutCommandUnCompleteData() {

        IoBuffer buf = IoBuffer.wrap("put test 1 5 0 10\r\nhel".getBytes());

        PutCommand decodedCmd = (PutCommand) this.decoder.decode(buf, null);
        assertNull(decodedCmd);
        assertEquals(0, buf.position());
        assertEquals(buf.capacity(), buf.remaining());

        buf = IoBuffer.wrap("put test 1 5 0 10\r\nhello".getBytes());
        decodedCmd = (PutCommand) this.decoder.decode(buf, null);
        assertNotNull(decodedCmd);
        assertEquals("test", decodedCmd.getTopic());
        assertEquals(1, decodedCmd.getPartition());
        assertEquals(0, decodedCmd.getFlag());
        assertEquals(10, (int) decodedCmd.getOpaque());
        assertEquals("hello", new String(decodedCmd.getData()));
        assertFalse(buf.hasRemaining());

    }


    @Test
    public void testDecodeTransactionCommand() {
        final IoBuffer buf = IoBuffer.wrap("transaction TX:sessionId:99 sessionId COMMIT_ONE_PHASE 100\r\n".getBytes());
        final TransactionCommand cmd = (TransactionCommand) this.decoder.decode(buf, null);
        assertNotNull(cmd);
        assertEquals(100, (int) cmd.getOpaque());
        final TransactionInfo info = cmd.getTransactionInfo();
        assertNotNull(info);
        assertNotNull(info.getTransactionId());
        assertTrue(info.getTransactionId().isLocalTransaction());
        final LocalTransactionId id = (LocalTransactionId) info.getTransactionId();
        assertEquals("sessionId", id.getSessionId());
        assertEquals(99, id.getValue());
    }
}
