package com.alibaba.napoli.metamorphosis.transaction;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

import java.util.Random;

import org.junit.Test;


public class TransactionIdUnitTest {
    private final Random rand = new Random();


    private byte[] randomBytes() {
        final byte[] bytes = new byte[this.rand.nextInt(100)];
        this.rand.nextBytes(bytes);
        return bytes;
    }


    @Test
    public void testValueOf() {
        final byte[] branchQualifier = this.randomBytes();
        final byte[] globalTransactionId = this.randomBytes();
        final XATransactionId xid = new XATransactionId(100, branchQualifier, globalTransactionId);
        final LocalTransactionId id = new LocalTransactionId("sessionId", -99);

        final String key1 = xid.getTransactionKey();
        final String key2 = id.getTransactionKey();

        assertEquals(xid, TransactionId.valueOf(key1));
        assertEquals(id, TransactionId.valueOf(key2));
    }


    @Test
    public void testXATransactionId() {
        final byte[] branchQualifier = this.randomBytes();
        final byte[] globalTransactionId = this.randomBytes();
        final XATransactionId id = new XATransactionId(100, branchQualifier, globalTransactionId);
        assertArrayEquals(branchQualifier, id.getBranchQualifier());
        assertArrayEquals(globalTransactionId, id.getGlobalTransactionId());
        assertEquals(100, id.getFormatId());

        final String key = id.getTransactionKey();
        assertNotNull(key);
        final XATransactionId newId = new XATransactionId(key);
        assertNotSame(id, newId);
        assertEquals(id, newId);
        assertEquals(0, id.compareTo(newId));

        assertTrue(id.isXATransaction());
        assertFalse(id.isLocalTransaction());
    }


    @Test
    public void testLocalTransactionId() {
        final LocalTransactionId id = new LocalTransactionId("sessionId", -99);
        assertFalse(id.isXATransaction());
        assertTrue(id.isLocalTransaction());
        assertEquals("sessionId", id.getSessionId());
        assertEquals(-99, id.getValue());
        final String s = id.getTransactionKey();
        assertNotNull(s);
        final LocalTransactionId newId = new LocalTransactionId(s);
        assertEquals(id, newId);
        assertEquals(0, id.compareTo(newId));
    }
}
