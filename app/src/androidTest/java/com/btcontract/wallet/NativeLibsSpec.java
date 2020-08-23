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

    @Test(timeout = 50000)
    public void scryptWorksFast() {
        assertTrue(Wally.isEnabled());
        WalletApp.scryptDerive("hello@email.com", "password123");
    }

    @Test(timeout = 5000)
    public void secpWorksFast() {
        WalletApp.getRandomMnemonic();
    }
}