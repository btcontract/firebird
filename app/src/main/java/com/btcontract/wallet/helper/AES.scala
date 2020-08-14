package com.btcontract.wallet.helper

import scodec.bits.{BitVector, ByteVector}
import com.btcontract.wallet.ln.crypto.Tools.{Bytes, random}
import javax.crypto.spec.{IvParameterSpec, SecretKeySpec}
import com.btcontract.wallet.ln.wire.ExtCodecs._
import com.btcontract.wallet.ln.wire.AESZygote
import javax.crypto.Cipher
import scala.util.Try


object AES {
  def cipher(key: Bytes, initVector: Bytes, mode: Int): Cipher =
    Cipher getInstance "AES/CBC/PKCS5Padding" match { case aesCipher =>
      val ivParameterSpec: IvParameterSpec = new IvParameterSpec(initVector)
      aesCipher.init(mode, new SecretKeySpec(key, "AES"), ivParameterSpec)
      aesCipher
    }

  private[this] val ivLength = 16
  def dec(data: Bytes, key: Bytes, initVector: Bytes): ByteVector = ByteVector.view(cipher(key, initVector, Cipher.DECRYPT_MODE) doFinal data)
  def enc(data: Bytes, key: Bytes, initVector: Bytes): ByteVector = ByteVector.view(cipher(key, initVector, Cipher.ENCRYPT_MODE) doFinal data)

  // Used for Object -> Scodec -> Encrypted -> Zygote

  def encBytes(plain: Bytes, key: Bytes): AESZygote = {
    val initialVector = ByteVector(random getBytes ivLength)
    val cipher = enc(plain, key, initialVector.toArray)
    AESZygote(v = 1, initialVector, cipher)
  }

  def decBytes(raw: Bytes, key: Bytes): Try[Bytes] = {
    val aesz = aesZygoteCodec decode BitVector.view(raw)
    decZygote(aesz.require.value, key)
  }

  def decZygote(aesz: AESZygote, key: Bytes): Try[Bytes] = Try {
    dec(aesz.ciphertext.toArray, key, aesz.iv.toArray).toArray
  }
}