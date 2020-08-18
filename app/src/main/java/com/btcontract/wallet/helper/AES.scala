package com.btcontract.wallet.helper

import javax.crypto.spec.{IvParameterSpec, SecretKeySpec}
import com.btcontract.wallet.ln.crypto.Tools.Bytes
import scodec.bits.ByteVector
import javax.crypto.Cipher


object AES {
  def cipher(key: Bytes, initVector: Bytes, mode: Int): Cipher =
    Cipher getInstance "AES/CBC/PKCS5Padding" match { case aesCipher =>
      val ivParameterSpec: IvParameterSpec = new IvParameterSpec(initVector)
      aesCipher.init(mode, new SecretKeySpec(key, "AES"), ivParameterSpec)
      aesCipher
    }

  def dec(data: Bytes, key: Bytes, initVector: Bytes): ByteVector = ByteVector.view(cipher(key, initVector, Cipher.DECRYPT_MODE) doFinal data)
  def enc(data: Bytes, key: Bytes, initVector: Bytes): ByteVector = ByteVector.view(cipher(key, initVector, Cipher.ENCRYPT_MODE) doFinal data)
}