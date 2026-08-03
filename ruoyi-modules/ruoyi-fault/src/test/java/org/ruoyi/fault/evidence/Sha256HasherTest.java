package org.ruoyi.fault.evidence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.ruoyi.fault.evidence.support.Sha256Hasher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("dev")
class Sha256HasherTest {

    private final Sha256Hasher hasher = new Sha256Hasher();

    @Test
    void hashesDeterministicallyWithStandardSha256Output() {
        String hash = hasher.hash("abc");

        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", hash);
        assertEquals(hash, hasher.hash("abc"));
        assertFalse(hash.equals(hasher.hash("abcd")));
        assertTrue(hash.matches("[0-9a-f]{64}"));
    }
}
