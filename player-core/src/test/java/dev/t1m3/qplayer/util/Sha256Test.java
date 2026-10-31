package dev.t1m3.qplayer.util;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class Sha256Test {
    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    /** The standard FIPS test vector for "abc". */
    private static final String ABC =
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

    @Test
    public void hashesFileContentsAsLowercaseHex() throws Exception {
        File file = temporary.newFile("abc.bin");
        Files.write(file.toPath(), "abc".getBytes(StandardCharsets.UTF_8));
        assertEquals(ABC, Sha256.of(file));
    }

    @Test
    public void matchesIsCaseInsensitiveAndRejectsEmptyExpectation() throws Exception {
        File file = temporary.newFile("abc.bin");
        Files.write(file.toPath(), "abc".getBytes(StandardCharsets.UTF_8));
        assertTrue(Sha256.matches(file, ABC));
        assertTrue(Sha256.matches(file, ABC.toUpperCase()));
        assertFalse(Sha256.matches(file, ""));
        assertFalse(Sha256.matches(file, null));
    }

    @Test
    public void missingOrUnreadableFileHasNoDigest() throws Exception {
        File missing = new File(temporary.getRoot(), "missing.bin");
        assertEquals("", Sha256.of(missing));
        assertFalse(Sha256.matches(missing, ABC));
        assertEquals("", Sha256.of(null));
    }
}
