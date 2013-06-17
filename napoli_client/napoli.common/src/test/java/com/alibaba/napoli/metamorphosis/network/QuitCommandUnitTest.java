package com.alibaba.napoli.metamorphosis.network;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import com.alibaba.napoli.gecko.core.buffer.IoBuffer;


public class QuitCommandUnitTest {
    @Test
    public void testEncode() {
        final QuitCommand cmd = new QuitCommand();
        final IoBuffer buf = cmd.encode();
        assertEquals(0, buf.position());
        assertEquals("quit\r\n", new String(buf.array()));
    }

}
