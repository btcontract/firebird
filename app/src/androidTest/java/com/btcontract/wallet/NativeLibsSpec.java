package com.btcontract.wallet;

import static org.junit.Assert.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.blockstream.libwally.Wally;
import org.bitcoin.Secp256k1Context;
import org.junit.runner.RunWith;
import org.junit.Test;


@RunWith(AndroidJUnit4.class)
public class NativeLibsSpec {
    @Test
    public void useAppContext() {
        assertTrue(Secp256k1Context.isEnabled());
        assertTrue(Wally.isEnabled());
    }
}