package com.btcontract.wallet;

import static org.junit.Assert.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.blockstream.libwally.Wally;
import org.bitcoin.Secp256k1Context;
import org.junit.runner.RunWith;
import org.junit.Test;

import java.nio.charset.StandardCharsets;

import fr.acinq.bitcoin.Base58;
import fr.acinq.bitcoin.ByteVector32;
import fr.acinq.bitcoin.ByteVector64;
import fr.acinq.bitcoin.Crypto;
import scala.Tuple2;
import scodec.bits.ByteVector;


@RunWith(AndroidJUnit4.class)
public class NativeLibsSpec {
    @Test
    public void useAppContext() {
        assertTrue(Secp256k1Context.isEnabled());
        assertTrue(Wally.isEnabled());
    }

    @Test(timeout = 50000)
    public void scryptWorksFast() {
        WalletApp.scryptDerive("hello@email.com", "password123");
    }

    @Test
    public void signAndVerifySig() {
        Crypto.PrivateKey privateKey = Crypto.PrivateKey$.MODULE$.fromBase58("cRp4uUnreGMZN8vB7nQFX6XWMHU5Lc73HMAhmcDEwHfbgRS66Cqp", Base58.Prefix$.MODULE$.SecretKeyTestnet())._1();
        Crypto.PublicKey publicKey = privateKey.publicKey();
        ByteVector32 data = Crypto.sha256().apply(ByteVector.view("this is a test".getBytes(StandardCharsets.UTF_8)));
        ByteVector64 sig = Crypto.sign(data.bytes(), privateKey);
        assertTrue(Crypto.verifySignature(data.bytes(), sig, publicKey));
    }

    @Test
    public void recoverPubKeyFromSig() {
        Crypto.PrivateKey privateKey = Crypto.PrivateKey$.MODULE$.fromBase58("cRp4uUnreGMZN8vB7nQFX6XWMHU5Lc73HMAhmcDEwHfbgRS66Cqp", Base58.Prefix$.MODULE$.SecretKeyTestnet())._1();
        ByteVector message = Crypto.sha256().apply(ByteVector.view("this is a message".getBytes(StandardCharsets.UTF_8))).bytes();
        Crypto.PublicKey pub = privateKey.publicKey();
        ByteVector64 sig64 = Crypto.sign(message, privateKey);
        Tuple2<Crypto.PublicKey, Crypto.PublicKey> res = Crypto.recoverPublicKey(sig64, message);

        assertTrue(Crypto.verifySignature(message, sig64, res._1()));
        assertTrue(Crypto.verifySignature(message, sig64, res._2()));
        assertTrue(pub.equals(res._1()) || pub.equals(res._2()));
    }
}