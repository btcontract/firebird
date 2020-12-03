package com.btcontract.wallet

import java.nio.ByteOrder

import fr.acinq.eclair._
import scodec.bits._
import scodec.codecs._
import scala.language.postfixOps
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.acinq.bitcoin.{Block, ByteVector32, Crypto, Protocol}
import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey}
import fr.acinq.eclair.Features.VariableLengthOnion
import fr.acinq.eclair.payment.PaymentRequest
import fr.acinq.eclair.payment.PaymentRequest._
import org.junit.runner.RunWith
import org.junit.Test
import scodec.DecodeResult

@RunWith(classOf[AndroidJUnit4])
class PaymentRequestSpec {
  val priv = PrivateKey(ByteVector.fromValidHex("e126f68f7eafcc8b74f54d269fe206be715000f94dac067d1c04a8ca3b2db734"))
  val pub: PublicKey = priv.publicKey
  val nodeId: PublicKey = pub
  assert(nodeId == PublicKey(ByteVector.fromValidHex("03e7156ae33b0a208d0744199163177e909e80176e55d97a2f221ede0f934dd9ad")))

  @Test
  def minimalUnitUsed(): Unit = {
    assert('p' == Amount.unit(1 msat))
    assert('p' == Amount.unit(99 msat))
    assert('n' == Amount.unit(100 msat))
    assert('p' == Amount.unit(101 msat))
    assert('n' == Amount.unit((1 sat).toMilliSatoshi))
    assert('u' == Amount.unit((100 sat).toMilliSatoshi))
    assert('n' == Amount.unit((101 sat).toMilliSatoshi))
    assert('u' == Amount.unit((1155400 sat).toMilliSatoshi))
    assert('m' == Amount.unit((1 mbtc).toMilliSatoshi))
    assert('m' == Amount.unit((10 mbtc).toMilliSatoshi))
    assert('m' == Amount.unit((1 btc).toMilliSatoshi))
  }

  @Test
  def decodeEmptyAmount(): Unit = {
    assert(Amount.decode("").isEmpty)
    assert(Amount.decode("0").isEmpty)
    assert(Amount.decode("0p").isEmpty)
    assert(Amount.decode("0n").isEmpty)
    assert(Amount.decode("0u").isEmpty)
    assert(Amount.decode("0m").isEmpty)
  }

  @Test
  def nonMinimalEncoding(): Unit = {
    assert(Amount.decode("1000u").contains(100000000 msat))
    assert(Amount.decode("1000000n").contains(100000000 msat))
    assert(Amount.decode("1000000000p").contains(100000000 msat))
  }

  @Test
  def stringToBitVector(): Unit = {
    assert(string2Bits("p") == BitVector.fromValidBin("00001"))
    assert(string2Bits("pz") == BitVector.fromValidBin("0000100010"))
  }

  @Test
  def minLength(): Unit = {
    assert(long2bits(0) == BitVector.fromValidBin(""))
    assert(long2bits(1) == BitVector.fromValidBin("00001"))
    assert(long2bits(42) == BitVector.fromValidBin("0000101010"))
    assert(long2bits(255) == BitVector.fromValidBin("0011111111"))
    assert(long2bits(256) == BitVector.fromValidBin("0100000000"))
    assert(long2bits(3600) == BitVector.fromValidBin("000111000010000"))
  }

  @Test
  def paddingIsZero(): Unit = {
    val codec = PaymentRequest.Codecs.alignedBytesCodec(bits)
    assert(codec.decode(bin"1010101000").require == DecodeResult(bin"10101010", BitVector.empty))
    assert(codec.decode(bin"1010101001").isFailure) // non-zero padding
  }

  @Test
  def invoice1(): Unit = {
    val ref = "lnbc1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdpl2pkx2ctnv5sxxmmwwd5kgetjypeh2ursdae8g6twvus8g6rfwvs8qun0dfjkxaq8rkx3yf5tcsyz3d73gafnh3cax9rn449d9p5uxz9ezhhypd0elx87sjle52x86fux2ypatgddc6k63n7erqz25le42c4u4ecky03ylcqca784w"
    val pr = PaymentRequest.read(ref)
    assert(pr.prefix == "lnbc")
    assert(pr.amount.isEmpty)
    assert(pr.paymentHash.bytes == hex"0001020304050607080900010203040506070809000102030405060708090102")
    assert(pr.timestamp == 1496314658L)
    assert(pr.nodeId == PublicKey(hex"03e7156ae33b0a208d0744199163177e909e80176e55d97a2f221ede0f934dd9ad"))
    assert(pr.description == Left("Please consider supporting this project"))
    assert(pr.fallbackAddress == None)
    assert(pr.tags.size == 2)
    assert(PaymentRequest.write(pr.sign(priv)) == ref)
  }

  @Test
  def invoice2(): Unit = {
    val ref = "lnbc2500u1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdq5xysxxatsyp3k7enxv4jsxqzpuaztrnwngzn3kdzw5hydlzf03qdgm2hdq27cqv3agm2awhz5se903vruatfhq77w3ls4evs3ch9zw97j25emudupq63nyw24cg27h2rspfj9srp"
    val pr = PaymentRequest.read(ref)
    assert(pr.prefix == "lnbc")
    assert(pr.amount == Some(250000000 msat))
    assert(pr.paymentHash.bytes == hex"0001020304050607080900010203040506070809000102030405060708090102")
    assert(pr.timestamp == 1496314658L)
    assert(pr.nodeId == PublicKey(hex"03e7156ae33b0a208d0744199163177e909e80176e55d97a2f221ede0f934dd9ad"))
    assert(pr.description == Left("1 cup coffee"))
    assert(pr.fallbackAddress == None)
    assert(pr.tags.size == 3)
    assert(PaymentRequest.write(pr.sign(priv)) == ref)
  }

  @Test
  def invoice3(): Unit = {
    val ref = "lnbc20m1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqhp58yjmdan79s6qqdhdzgynm4zwqd5d7xmw5fk98klysy043l2ahrqscc6gd6ql3jrc5yzme8v4ntcewwz5cnw92tz0pc8qcuufvq7khhr8wpald05e92xw006sq94mg8v2ndf4sefvf9sygkshp5zfem29trqq2yxxz7"
    val pr = PaymentRequest.read(ref)
    assert(pr.prefix == "lnbc")
    assert(pr.amount == Some(2000000000 msat))
    assert(pr.paymentHash.bytes == hex"0001020304050607080900010203040506070809000102030405060708090102")
    assert(pr.timestamp == 1496314658L)
    assert(pr.nodeId == PublicKey(hex"03e7156ae33b0a208d0744199163177e909e80176e55d97a2f221ede0f934dd9ad"))
    assert(pr.description == Right(Crypto.sha256(ByteVector("One piece of chocolate cake, one icecream cone, one pickle, one slice of swiss cheese, one slice of salami, one lollypop, one piece of cherry pie, one sausage, one cupcake, and one slice of watermelon".getBytes))))
    assert(pr.fallbackAddress == None)
    assert(pr.tags.size == 2)
    assert(PaymentRequest.write(pr.sign(priv)) == ref)
  }

  @Test
  def invoice4(): Unit = {
    val ref = "lntb20m1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqhp58yjmdan79s6qqdhdzgynm4zwqd5d7xmw5fk98klysy043l2ahrqsfpp3x9et2e20v6pu37c5d9vax37wxq72un98k6vcx9fz94w0qf237cm2rqv9pmn5lnexfvf5579slr4zq3u8kmczecytdx0xg9rwzngp7e6guwqpqlhssu04sucpnz4axcv2dstmknqq6jsk2l"
    val pr = PaymentRequest.read(ref)
    assert(pr.prefix == "lntb")
    assert(pr.amount == Some(2000000000 msat))
    assert(pr.paymentHash.bytes == hex"0001020304050607080900010203040506070809000102030405060708090102")
    assert(pr.timestamp == 1496314658L)
    assert(pr.nodeId == PublicKey(hex"03e7156ae33b0a208d0744199163177e909e80176e55d97a2f221ede0f934dd9ad"))
    assert(pr.description == Right(Crypto.sha256(ByteVector.view("One piece of chocolate cake, one icecream cone, one pickle, one slice of swiss cheese, one slice of salami, one lollypop, one piece of cherry pie, one sausage, one cupcake, and one slice of watermelon".getBytes))))
    assert(pr.fallbackAddress == Some("mk2QpYatsKicvFVuTAQLBryyccRXMUaGHP"))
    assert(pr.tags.size == 3)
    assert(PaymentRequest.write(pr.sign(priv)) == ref)
  }

  @Test
  def invoice5(): Unit = {
    val ref = "lnbc20m1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqhp58yjmdan79s6qqdhdzgynm4zwqd5d7xmw5fk98klysy043l2ahrqsfpp3qjmp7lwpagxun9pygexvgpjdc4jdj85fr9yq20q82gphp2nflc7jtzrcazrra7wwgzxqc8u7754cdlpfrmccae92qgzqvzq2ps8pqqqqqqpqqqqq9qqqvpeuqafqxu92d8lr6fvg0r5gv0heeeqgcrqlnm6jhphu9y00rrhy4grqszsvpcgpy9qqqqqqgqqqqq7qqzqj9n4evl6mr5aj9f58zp6fyjzup6ywn3x6sk8akg5v4tgn2q8g4fhx05wf6juaxu9760yp46454gpg5mtzgerlzezqcqvjnhjh8z3g2qqdhhwkj"
    val pr = PaymentRequest.read(ref)
    assert(pr.prefix == "lnbc")
    assert(pr.amount == Some(2000000000 msat))
    assert(pr.paymentHash.bytes == hex"0001020304050607080900010203040506070809000102030405060708090102")
    assert(pr.timestamp == 1496314658L)
    assert(pr.nodeId == PublicKey(hex"03e7156ae33b0a208d0744199163177e909e80176e55d97a2f221ede0f934dd9ad"))
    assert(pr.description == Right(Crypto.sha256(ByteVector.view("One piece of chocolate cake, one icecream cone, one pickle, one slice of swiss cheese, one slice of salami, one lollypop, one piece of cherry pie, one sausage, one cupcake, and one slice of watermelon".getBytes))))
    assert(pr.fallbackAddress == Some("1RustyRX2oai4EYYDpQGWvEL62BBGqN9T"))
    assert(pr.routingInfo == List(List(
      ExtraHop(PublicKey(hex"029e03a901b85534ff1e92c43c74431f7ce72046060fcf7a95c37e148f78c77255"), ShortChannelId(72623859790382856L), 1 msat, 20, CltvExpiryDelta(3)),
      ExtraHop(PublicKey(hex"039e03a901b85534ff1e92c43c74431f7ce72046060fcf7a95c37e148f78c77255"), ShortChannelId(217304205466536202L), 2 msat, 30, CltvExpiryDelta(4))
    )))
    assert(Protocol.writeUInt64(0x0102030405060708L, ByteOrder.BIG_ENDIAN) == hex"0102030405060708")
    assert(Protocol.writeUInt64(0x030405060708090aL, ByteOrder.BIG_ENDIAN) == hex"030405060708090a")
    assert(pr.tags.size == 4)
    assert(PaymentRequest.write(pr.sign(priv)) == ref)
  }

  @Test
  def invoice6(): Unit = {
    val ref = "lnbc20m1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqhp58yjmdan79s6qqdhdzgynm4zwqd5d7xmw5fk98klysy043l2ahrqsfppj3a24vwu6r8ejrss3axul8rxldph2q7z9kk822r8plup77n9yq5ep2dfpcydrjwzxs0la84v3tfw43t3vqhek7f05m6uf8lmfkjn7zv7enn76sq65d8u9lxav2pl6x3xnc2ww3lqpagnh0u"
    val pr = PaymentRequest.read(ref)
    assert(pr.prefix == "lnbc")
    assert(pr.amount == Some(2000000000 msat))
    assert(pr.paymentHash.bytes == hex"0001020304050607080900010203040506070809000102030405060708090102")
    assert(pr.timestamp == 1496314658L)
    assert(pr.nodeId == PublicKey(hex"03e7156ae33b0a208d0744199163177e909e80176e55d97a2f221ede0f934dd9ad"))
    assert(pr.description == Right(Crypto.sha256(ByteVector.view("One piece of chocolate cake, one icecream cone, one pickle, one slice of swiss cheese, one slice of salami, one lollypop, one piece of cherry pie, one sausage, one cupcake, and one slice of watermelon".getBytes))))
    assert(pr.fallbackAddress == Some("3EktnHQD7RiAE6uzMj2ZifT9YgRrkSgzQX"))
    assert(pr.tags.size == 3)
    assert(PaymentRequest.write(pr.sign(priv)) == ref)
  }

  @Test
  def invoice7(): Unit = {
    val ref = "lnbc20m1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqhp58yjmdan79s6qqdhdzgynm4zwqd5d7xmw5fk98klysy043l2ahrqsfppqw508d6qejxtdg4y5r3zarvary0c5xw7kknt6zz5vxa8yh8jrnlkl63dah48yh6eupakk87fjdcnwqfcyt7snnpuz7vp83txauq4c60sys3xyucesxjf46yqnpplj0saq36a554cp9wt865"
    val pr = PaymentRequest.read(ref)
    assert(pr.prefix == "lnbc")
    assert(pr.amount == Some(2000000000 msat))
    assert(pr.paymentHash.bytes == hex"0001020304050607080900010203040506070809000102030405060708090102")
    assert(pr.timestamp == 1496314658L)
    assert(pr.nodeId == PublicKey(hex"03e7156ae33b0a208d0744199163177e909e80176e55d97a2f221ede0f934dd9ad"))
    assert(pr.description == Right(Crypto.sha256(ByteVector.view("One piece of chocolate cake, one icecream cone, one pickle, one slice of swiss cheese, one slice of salami, one lollypop, one piece of cherry pie, one sausage, one cupcake, and one slice of watermelon".getBytes))))
    assert(pr.fallbackAddress == Some("bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4"))
    assert(pr.tags.size == 3)
    assert(PaymentRequest.write(pr.sign(priv)) == ref)
  }

  @Test
  def invoice8(): Unit = {
    val ref = "lnbc20m1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqhp58yjmdan79s6qqdhdzgynm4zwqd5d7xmw5fk98klysy043l2ahrqsfp4qrp33g0q5c5txsp9arysrx4k6zdkfs4nce4xj0gdcccefvpysxf3qvnjha2auylmwrltv2pkp2t22uy8ura2xsdwhq5nm7s574xva47djmnj2xeycsu7u5v8929mvuux43j0cqhhf32wfyn2th0sv4t9x55sppz5we8"
    val pr = PaymentRequest.read(ref)
    assert(pr.prefix == "lnbc")
    assert(pr.amount == Some(2000000000 msat))
    assert(pr.paymentHash.bytes == hex"0001020304050607080900010203040506070809000102030405060708090102")
    assert(pr.timestamp == 1496314658L)
    assert(pr.nodeId == PublicKey(hex"03e7156ae33b0a208d0744199163177e909e80176e55d97a2f221ede0f934dd9ad"))
    assert(pr.description == Right(Crypto.sha256(ByteVector.view("One piece of chocolate cake, one icecream cone, one pickle, one slice of swiss cheese, one slice of salami, one lollypop, one piece of cherry pie, one sausage, one cupcake, and one slice of watermelon".getBytes))))
    assert(pr.fallbackAddress == Some("bc1qrp33g0q5c5txsp9arysrx4k6zdkfs4nce4xj0gdcccefvpysxf3qccfmv3"))
    assert(pr.tags.size == 3)
    assert(pr.features.bitmask.isEmpty)
    assert(!pr.features.allowMultiPart)
    assert(PaymentRequest.write(pr.sign(priv)) == ref)
  }

  @Test
  def invoice9(): Unit = {
    val ref = "lnbc20m1pvjluezcqpvpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqhp58yjmdan79s6qqdhdzgynm4zwqd5d7xmw5fk98klysy043l2ahrqsfp4qrp33g0q5c5txsp9arysrx4k6zdkfs4nce4xj0gdcccefvpysxf3q90qkf3gd7fcqs0ewr7t3xf72ptmc4n38evg0xhy4p64nlg7hgrmq6g997tkrvezs8afs0x0y8v4vs8thwsk6knkvdfvfa7wmhhpcsxcqw0ny48"
    val pr = PaymentRequest.read(ref)
    assert(pr.prefix == "lnbc")
    assert(pr.amount == Some(2000000000 msat))
    assert(pr.paymentHash.bytes == hex"0001020304050607080900010203040506070809000102030405060708090102")
    assert(pr.timestamp == 1496314658L)
    assert(pr.nodeId == PublicKey(hex"03e7156ae33b0a208d0744199163177e909e80176e55d97a2f221ede0f934dd9ad"))
    assert(pr.description == Right(Crypto.sha256(ByteVector.view("One piece of chocolate cake, one icecream cone, one pickle, one slice of swiss cheese, one slice of salami, one lollypop, one piece of cherry pie, one sausage, one cupcake, and one slice of watermelon".getBytes))))
    assert(pr.fallbackAddress == Some("bc1qrp33g0q5c5txsp9arysrx4k6zdkfs4nce4xj0gdcccefvpysxf3qccfmv3"))
    assert(pr.minFinalCltvExpiryDelta == Some(CltvExpiryDelta(12)))
    assert(pr.tags.size == 4)
    assert(pr.features.bitmask.isEmpty)
    assert(!pr.features.allowMultiPart)
    assert(PaymentRequest.write(pr.sign(priv)) == ref)
  }

  @Test
  def invoice10(): Unit = {
    val refs = Seq(
      "lnbc25m1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdq5vdhkven9v5sxyetpdeessp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygs9q5sqqqqqqqqqqqqqqqpqsq67gye39hfg3zd8rgc80k32tvy9xk2xunwm5lzexnvpx6fd77en8qaq424dxgt56cag2dpt359k3ssyhetktkpqh24jqnjyw6uqd08sgptq44qu",
      // All upper-case
      "lnbc25m1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdq5vdhkven9v5sxyetpdeessp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygs9q5sqqqqqqqqqqqqqqqpqsq67gye39hfg3zd8rgc80k32tvy9xk2xunwm5lzexnvpx6fd77en8qaq424dxgt56cag2dpt359k3ssyhetktkpqh24jqnjyw6uqd08sgptq44qu".toUpperCase,
      // With ignored fields
      "lnbc25m1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdq5vdhkven9v5sxyetpdeessp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygs9q5sqqqqqqqqqqqqqqqpqsq2qrqqqfppnqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqppnqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqpp4qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqhpnqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqhp4qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqspnqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqsp4qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqnp5qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqnpkqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq2jxxfsnucm4jf4zwtznpaxphce606fvhvje5x7d4gw7n73994hgs7nteqvenq8a4ml8aqtchv5d9pf7l558889hp4yyrqv6a7zpq9fgpskqhza"
    )

    for (ref <- refs) {
      val pr = PaymentRequest.read(ref)
      assert(pr.prefix == "lnbc")
      assert(pr.amount == Some(2500000000L msat))
      assert(pr.paymentHash.bytes === hex"0001020304050607080900010203040506070809000102030405060708090102")
      assert(pr.paymentSecret == Some(ByteVector32(hex"1111111111111111111111111111111111111111111111111111111111111111")))
      assert(pr.timestamp == 1496314658L)
      assert(pr.nodeId == PublicKey(hex"03e7156ae33b0a208d0744199163177e909e80176e55d97a2f221ede0f934dd9ad"))
      assert(pr.description == Left("coffee beans"))
      assert(pr.features.bitmask === bin"1000000000000000000000000000000000000000000000000000000000000000000000000000000000001000001000000000")
      assert(!pr.features.allowMultiPart)
      assert(!pr.features.requirePaymentSecret)
      assert(pr.features.supported)
      assert(PaymentRequest.write(pr.sign(priv)) == ref.toLowerCase)
    }
  }

  @Test
  def invoice11(): Unit = {
    val ref = "lnbc25m1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdq5vdhkven9v5sxyetpdeessp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygs9q4psqqqqqqqqqqqqqqqpqsqq40wa3khl49yue3zsgm26jrepqr2eghqlx86rttutve3ugd05em86nsefzh4pfurpd9ek9w2vp95zxqnfe2u7ckudyahsa52q66tgzcp6t2dyk"
    val pr = PaymentRequest.read(ref)
    assert(pr.prefix == "lnbc")
    assert(pr.amount == Some(2500000000L msat))
    assert(pr.paymentHash.bytes === hex"0001020304050607080900010203040506070809000102030405060708090102")
    assert(pr.paymentSecret == Some(ByteVector32(hex"1111111111111111111111111111111111111111111111111111111111111111")))
    assert(pr.timestamp == 1496314658L)
    assert(pr.nodeId == PublicKey(hex"03e7156ae33b0a208d0744199163177e909e80176e55d97a2f221ede0f934dd9ad"))
    assert(pr.description == Left("coffee beans"))
    assert(pr.fallbackAddress().isEmpty)
    assert(pr.features.bitmask === bin"000011000000000000000000000000000000000000000000000000000000000000000000000000000000000001000001000000000")
    assert(!pr.features.allowMultiPart)
    assert(!pr.features.requirePaymentSecret)
    assert(!pr.features.supported)
    assert(PaymentRequest.write(pr.sign(priv)) == ref)
  }

  @Test
  def invoice12(): Unit = {
    val ref = "lnbc9678785340p1pwmna7lpp5gc3xfm08u9qy06djf8dfflhugl6p7lgza6dsjxq454gxhj9t7a0sd8dgfkx7cmtwd68yetpd5s9xar0wfjn5gpc8qhrsdfq24f5ggrxdaezqsnvda3kkum5wfjkzmfqf3jkgem9wgsyuctwdus9xgrcyqcjcgpzgfskx6eqf9hzqnteypzxz7fzypfhg6trddjhygrcyqezcgpzfysywmm5ypxxjemgw3hxjmn8yptk7untd9hxwg3q2d6xjcmtv4ezq7pqxgsxzmnyyqcjqmt0wfjjq6t5v4khxxqyjw5qcqp2rzjq0gxwkzc8w6323m55m4jyxcjwmy7stt9hwkwe2qxmy8zpsgg7jcuwz87fcqqeuqqqyqqqqlgqqqqn3qq9qn07ytgrxxzad9hc4xt3mawjjt8znfv8xzscs7007v9gh9j569lencxa8xeujzkxs0uamak9aln6ez02uunw6rd2ht2sqe4hz8thcdagpleym0j"
    val pr = PaymentRequest.read(ref)
    assert(pr.prefix == "lnbc")
    assert(pr.amount == Some(967878534 msat))
    assert(pr.paymentHash.bytes === hex"462264ede7e14047e9b249da94fefc47f41f7d02ee9b091815a5506bc8abf75f")
    assert(pr.timestamp == 1572468703L)
    assert(pr.nodeId == PublicKey(hex"03e7156ae33b0a208d0744199163177e909e80176e55d97a2f221ede0f934dd9ad"))
    assert(pr.description == Left("Blockstream Store: 88.85 USD for Blockstream Ledger Nano S x 1, \"Back In My Day\" Sticker x 2, \"I Got Lightning Working\" Sticker x 2 and 1 more items"))
    assert(pr.fallbackAddress().isEmpty)
    assert(pr.expiry == Some(604800L))
    assert(pr.minFinalCltvExpiryDelta == Some(CltvExpiryDelta(10)))
    assert(pr.routingInfo == Seq(Seq(ExtraHop(PublicKey(hex"03d06758583bb5154774a6eb221b1276c9e82d65bbaceca806d90e20c108f4b1c7"), ShortChannelId("589390x3312x1"), 1000 msat, 2500, CltvExpiryDelta(40)))))
    assert(pr.features.supported)
    assert(PaymentRequest.write(pr.sign(priv)) == ref)
  }

  @Test
  def variableLengthFields(): Unit = {
    val number = 123456
    val codec = PaymentRequest.Codecs.dataCodec(scodec.codecs.bits).as[PaymentRequest.Expiry]
    val field = PaymentRequest.Expiry(number)
    assert(field.toLong == number)

    val serializedExpiry = codec.encode(field).require
    val field1 = codec.decodeValue(serializedExpiry).require
    assert(field1 == field)

    // Now with a payment request
    val pr = PaymentRequest(chainHash = Block.LivenetGenesisBlock.hash, amount = Some(123 msat), paymentHash = ByteVector32(ByteVector.fill(32)(1)), privateKey = priv, description = "Some invoice", minFinalCltvExpiryDelta = CltvExpiryDelta(18), expirySeconds = Some(123456), timestamp = 12345)
    assert(pr.minFinalCltvExpiryDelta.contains(CltvExpiryDelta(18)))
    val serialized = PaymentRequest.write(pr)
    val pr1 = PaymentRequest.read(serialized)
    assert(pr == pr1)
  }

  @Test
  def ignoreUnknownTags(): Unit = {
    val pr = PaymentRequest(
      prefix = "lntb",
      amount = Some(100000 msat),
      timestamp = System.currentTimeMillis() / 1000L,
      nodeId = nodeId,
      tags = List(
        PaymentHash(ByteVector32(ByteVector.fill(32)(1))),
        Description("description"),
        UnknownTag21(BitVector("some data we don't understand".getBytes))
      ),
      signature = ByteVector.empty).sign(priv)

    val serialized = PaymentRequest.write(pr)
    val pr1 = PaymentRequest.read(serialized)
    val Some(_) = pr1.tags.collectFirst { case u: UnknownTag21 => u }
  }

  @Test
  def ignoreInvalidTags(): Unit = {
    // Bolt11: A reader: MUST skip over p, h, s or n fields that do NOT have data_lengths of 52, 52, 52 or 53, respectively.
    def bits(i: Int) = BitVector.fill(i * 5)(high = false)

    val inputs = Map(
      "ppnqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq" -> InvalidTag1(bits(51)),
      "pp4qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq" -> InvalidTag1(bits(53)),
      "hpnqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq" -> InvalidTag23(bits(51)),
      "hp4qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq" -> InvalidTag23(bits(53)),
      "spnqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq" -> InvalidTag16(bits(51)),
      "sp4qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq" -> InvalidTag16(bits(53)),
      "np5qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq" -> UnknownTag19(bits(52)),
      "npkqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq" -> UnknownTag19(bits(54))
    )

    for ((input, value) <- inputs) {
      val data = string2Bits(input)
      val decoded = Codecs.taggedFieldCodec.decode(data).require.value
      assert(decoded == value)
      val encoded = Codecs.taggedFieldCodec.encode(value).require
      assert(encoded == data)
    }
  }

  @Test
  def acceptUppercase(): Unit = {
    val input = "lntb1500n1pwxx94fpp5q3xzmwuvxpkyhz6pvg3fcfxz0259kgh367qazj62af9rs0pw07dsdpa2fjkzep6yp58garswvaz7tmvd9nksarwd9hxw6n0w4kx2tnrdakj7grfwvs8wcqzysxqr23sjzv0d8794te26xhexuc26eswf9sjpv4t8sma2d9y8dmpgf0qseg8259my8tcs6zte7ex0tz4exm5pjezuxrq9u0vjewa02qhedk9x4gppweupu"

    assert(PaymentRequest.write(PaymentRequest.read(input.toUpperCase())) == input)
  }

  @Test
  def oneBtcNoMultiplyer(): Unit = {
    val ref = "lnbc11pdkmqhupp5n2ees808r98m0rh4472yyth0c5fptzcxmexcjznrzmq8xald0cgqdqsf4ujqarfwqsxymmccqp2xvtsv5tc743wgctlza8k3zlpxucl7f3kvjnjptv7xz0nkaww307sdyrvgke2w8kmq7dgz4lkasfn0zvplc9aa4gp8fnhrwfjny0j59sq42x9gp"
    val pr = PaymentRequest.read(ref)
    assert(pr.amount.contains(100000000000L msat))
    assert(pr.features.bitmask === BitVector.empty)
  }

  @Test
  def minimallyEncodedFeatureBits(): Unit = {
    val testCases = Seq(
      (bin"   0010000100000101", hex"  2105"),
      (bin"   1010000100000101", hex"  a105"),
      (bin"  11000000000000110", hex"018006"),
      (bin"  01000000000000110", hex"  8006"),
      (bin" 001000000000000000", hex"  8000"),
      (bin" 101000000000000000", hex"028000"),
      (bin"0101110000000000110", hex"02e006"),
      (bin"1001110000000000110", hex"04e006")
    )

    for ((bitmask, featureBytes) <- testCases) {
      assert(PaymentRequestFeatures(bitmask).toByteVector === featureBytes)
    }
  }

  @Test
  def paymentSecret(): Unit = {
    val pr = PaymentRequest(Block.LivenetGenesisBlock.hash, Some(123 msat), ByteVector32.One, priv, "Some invoice", CltvExpiryDelta(18))
    assert(pr.paymentSecret.isDefined)
    assert(pr.features == PaymentRequestFeatures(Features.PaymentSecret.optional, VariableLengthOnion.optional))
    assert(!pr.features.requirePaymentSecret)

    val pr1 = PaymentRequest.read(PaymentRequest.write(pr))
    assert(pr1.paymentSecret == pr.paymentSecret)

    val pr2 = PaymentRequest.read("lnbc40n1pw9qjvwpp5qq3w2ln6krepcslqszkrsfzwy49y0407hvks30ec6pu9s07jur3sdpstfshq5n9v9jzucm0d5s8vmm5v5s8qmmnwssyj3p6yqenwdencqzysxqrrss7ju0s4dwx6w8a95a9p2xc5vudl09gjl0w2n02sjrvffde632nxwh2l4w35nqepj4j5njhh4z65wyfc724yj6dn9wajvajfn5j7em6wsq2elakl")
    assert(!pr2.features.requirePaymentSecret)
    assert(pr2.paymentSecret.isEmpty)
  }
}
