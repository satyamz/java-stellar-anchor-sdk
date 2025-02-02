@file:Suppress("unused")

package org.stellar.anchor.sep10

import com.google.common.io.BaseEncoding
import com.google.gson.annotations.SerializedName
import io.mockk.MockKAnnotations
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.spyk
import io.mockk.verify
import java.io.IOException
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import java.util.stream.Stream
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import org.stellar.anchor.LockAndMockStatic
import org.stellar.anchor.LockAndMockTest
import org.stellar.anchor.TestConstants.Companion.TEST_ACCOUNT
import org.stellar.anchor.TestConstants.Companion.TEST_CLIENT_DOMAIN
import org.stellar.anchor.TestConstants.Companion.TEST_CLIENT_TOML
import org.stellar.anchor.TestConstants.Companion.TEST_HOME_DOMAIN
import org.stellar.anchor.TestConstants.Companion.TEST_JWT_SECRET
import org.stellar.anchor.TestConstants.Companion.TEST_MEMO
import org.stellar.anchor.TestConstants.Companion.TEST_SIGNING_SEED
import org.stellar.anchor.TestConstants.Companion.TEST_WEB_AUTH_DOMAIN
import org.stellar.anchor.api.exception.SepException
import org.stellar.anchor.api.exception.SepNotAuthorizedException
import org.stellar.anchor.api.exception.SepValidationException
import org.stellar.anchor.api.sep.sep10.ChallengeRequest
import org.stellar.anchor.api.sep.sep10.ValidationRequest
import org.stellar.anchor.auth.JwtService
import org.stellar.anchor.auth.Sep10Jwt
import org.stellar.anchor.config.AppConfig
import org.stellar.anchor.config.CustodySecretConfig
import org.stellar.anchor.config.SecretConfig
import org.stellar.anchor.config.Sep10Config
import org.stellar.anchor.horizon.Horizon
import org.stellar.anchor.util.FileUtil
import org.stellar.anchor.util.GsonUtils
import org.stellar.anchor.util.NetUtil
import org.stellar.sdk.*
import org.stellar.sdk.Network.TESTNET
import org.stellar.sdk.requests.ErrorResponse
import org.stellar.sdk.responses.AccountResponse

@Suppress("unused")
internal class TestSigner(
  @SerializedName("key") val key: String,
  @SerializedName("type") val type: String,
  @SerializedName("weight") val weight: Int,
  @SerializedName("sponsor") val sponsor: String
) {
  fun toSigner(): AccountResponse.Signer {
    val gson = GsonUtils.getInstance()
    val json = gson.toJson(this)
    return gson.fromJson(json, AccountResponse.Signer::class.java)
  }
}

fun `create httpClient`(): OkHttpClient {
  return OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.MINUTES)
    .readTimeout(10, TimeUnit.MINUTES)
    .writeTimeout(10, TimeUnit.MINUTES)
    .hostnameVerifier { _, _ -> true }
    .build()
}

@ExtendWith(LockAndMockTest::class)
internal class Sep10ServiceTest {
  companion object {
    @JvmStatic
    fun homeDomains(): Stream<String> {
      return Stream.of(null, TEST_HOME_DOMAIN)
    }

    val testAccountWithNonCompliantSigner: String =
      FileUtil.getResourceFileAsString("test_account_with_noncompliant_signer.json")
  }

  @MockK(relaxed = true) lateinit var appConfig: AppConfig
  @MockK(relaxed = true) lateinit var secretConfig: SecretConfig
  @MockK(relaxed = true) lateinit var custodySecretConfig: CustodySecretConfig
  @MockK(relaxed = true) lateinit var sep10Config: Sep10Config
  @MockK(relaxed = true) lateinit var horizon: Horizon

  private lateinit var jwtService: JwtService
  private lateinit var sep10Service: Sep10Service
  private lateinit var httpClient: OkHttpClient
  private val clientKeyPair: KeyPair = KeyPair.random()
  private val clientDomainKeyPair: KeyPair = KeyPair.random()

  @BeforeEach
  fun setUp() {
    MockKAnnotations.init(this, relaxUnitFun = true)
    every { sep10Config.webAuthDomain } returns TEST_WEB_AUTH_DOMAIN
    every { sep10Config.authTimeout } returns 900
    every { sep10Config.jwtTimeout } returns 900
    every { sep10Config.homeDomains } returns listOf(TEST_HOME_DOMAIN)

    every { appConfig.stellarNetworkPassphrase } returns TESTNET.networkPassphrase

    every { secretConfig.sep10SigningSeed } returns TEST_SIGNING_SEED
    every { secretConfig.sep10JwtSecretKey } returns TEST_JWT_SECRET

    this.jwtService = spyk(JwtService(secretConfig, custodySecretConfig))
    this.sep10Service = Sep10Service(appConfig, secretConfig, sep10Config, horizon, jwtService)
    this.httpClient = `create httpClient`()
  }

  @Synchronized
  fun createTestChallenge(
    clientDomain: String,
    homeDomain: String,
    signWithClientDomain: Boolean
  ): String {
    val now = System.currentTimeMillis() / 1000L
    val signer = KeyPair.fromSecretSeed(TEST_SIGNING_SEED)
    val memo = MemoId(TEST_MEMO.toLong())
    val txn =
      Sep10ChallengeWrapper.instance()
        .newChallenge(
          signer,
          Network(TESTNET.networkPassphrase),
          clientKeyPair.accountId,
          homeDomain,
          TEST_WEB_AUTH_DOMAIN,
          TimeBounds(now, now + 900),
          clientDomain,
          if (clientDomain.isEmpty()) "" else clientDomainKeyPair.accountId,
          memo
        )
    txn.sign(clientKeyPair)
    if (clientDomain.isNotEmpty() && signWithClientDomain) {
      txn.sign(clientDomainKeyPair)
    }
    return txn.toEnvelopeXdrBase64()
  }

  @Test
  fun `test challenge with non existent account and client domain`() {
    // 1 ------ Create Test Transaction

    // serverKP does not exist in the network.
    val serverWebAuthDomain = TEST_WEB_AUTH_DOMAIN
    val serverHomeDomain = TEST_HOME_DOMAIN
    val serverKP = KeyPair.random()

    // clientDomainKP does not exist in the network. It refers to the wallet (like Lobstr's)
    // account.
    val clientDomainKP = KeyPair.random()

    // The public key of the client that DOES NOT EXIST.
    val clientKP = KeyPair.random()

    val nonce = ByteArray(48)
    val random = SecureRandom()
    random.nextBytes(nonce)
    val base64Encoding = BaseEncoding.base64()
    val encodedNonce = base64Encoding.encode(nonce).toByteArray()

    val sourceAccount = Account(serverKP.accountId, -1L)
    val op1DomainNameMandatory =
      ManageDataOperation.Builder("$serverHomeDomain auth", encodedNonce)
        .setSourceAccount(clientKP.accountId)
        .build()
    val op2WebAuthDomainMandatory =
      ManageDataOperation.Builder("web_auth_domain", serverWebAuthDomain.toByteArray())
        .setSourceAccount(serverKP.accountId)
        .build()
    val op3clientDomainOptional =
      ManageDataOperation.Builder("client_domain", "lobstr.co".toByteArray())
        .setSourceAccount(clientDomainKP.accountId)
        .build()

    val transaction =
      TransactionBuilder(AccountConverter.enableMuxed(), sourceAccount, TESTNET)
        .addPreconditions(
          TransactionPreconditions.builder().timeBounds(TimeBounds.expiresAfter(900)).build()
        )
        .setBaseFee(100)
        .addOperation(op1DomainNameMandatory)
        .addOperation(op2WebAuthDomainMandatory)
        .addOperation(op3clientDomainOptional)
        .build()

    transaction.sign(serverKP)
    transaction.sign(clientDomainKP)
    transaction.sign(clientKP)

    // 2 ------ Create Services
    every { secretConfig.sep10SigningSeed } returns String(serverKP.secretSeed)
    every { appConfig.horizonUrl } returns "https://horizon-testnet.stellar.org"
    every { appConfig.stellarNetworkPassphrase } returns TESTNET.networkPassphrase
    val horizon = Horizon(appConfig)
    this.sep10Service = Sep10Service(appConfig, secretConfig, sep10Config, horizon, jwtService)

    // 3 ------ Run tests
    val validationRequest = ValidationRequest.of(transaction.toEnvelopeXdrBase64())
    assertDoesNotThrow { sep10Service.validateChallenge(validationRequest) }
  }

  @Test
  fun `test challenge with existent account multisig with invalid ed dsa public key and client domain`() {
    // 1 ------ Mock client account and its response from horizon
    // The public key of the client that exists thanks to a mockk
    // GDFWZYGUNUFW4H3PP3DSNGTDFBUHO6NUFPQ6FAPMCKEJ6EHDKX2CV2IM
    val clientKP =
      KeyPair.fromSecretSeed("SAUNXQPM7VDH3WMDRHJ2WIN27KD23XD4AZPE62V76Q2SJPXR3DQWEOPX")
    val mockHorizon = MockWebServer()
    mockHorizon.start()

    mockHorizon.enqueue(
      MockResponse()
        .addHeader("Content-Type", "application/json")
        .setBody(testAccountWithNonCompliantSigner)
    )
    val mockHorizonUrl = mockHorizon.url("").toString()

    // 2 ------ Create Test Transaction

    // serverKP does not exist in the network.
    val serverWebAuthDomain = TEST_WEB_AUTH_DOMAIN
    val serverHomeDomain = TEST_HOME_DOMAIN
    // GDFWZYGUNUFW4H3PP3DSNGTDFBUHO6NUFPQ6FAPMCKEJ6EHDKX2CV2IM
    val serverKP = KeyPair.random()

    // clientDomainKP does not exist in the network. It refers to the wallet (like Lobstr's)
    // account.
    val clientDomainKP = KeyPair.random()

    val nonce = ByteArray(48)
    val random = SecureRandom()
    random.nextBytes(nonce)
    val base64Encoding = BaseEncoding.base64()
    val encodedNonce = base64Encoding.encode(nonce).toByteArray()

    val sourceAccount = Account(serverKP.accountId, -1L)
    val op1DomainNameMandatory =
      ManageDataOperation.Builder("$serverHomeDomain auth", encodedNonce)
        .setSourceAccount(clientKP.accountId)
        .build()
    val op2WebAuthDomainMandatory =
      ManageDataOperation.Builder("web_auth_domain", serverWebAuthDomain.toByteArray())
        .setSourceAccount(serverKP.accountId)
        .build()
    val op3clientDomainOptional =
      ManageDataOperation.Builder("client_domain", "lobstr.co".toByteArray())
        .setSourceAccount(clientDomainKP.accountId)
        .build()

    val transaction =
      TransactionBuilder(AccountConverter.enableMuxed(), sourceAccount, TESTNET)
        .addPreconditions(
          TransactionPreconditions.builder().timeBounds(TimeBounds.expiresAfter(900)).build()
        )
        .setBaseFee(100)
        .addOperation(op1DomainNameMandatory)
        .addOperation(op2WebAuthDomainMandatory)
        .addOperation(op3clientDomainOptional)
        .build()

    transaction.sign(serverKP)
    transaction.sign(clientDomainKP)
    transaction.sign(clientKP)

    // 2 ------ Create Services
    every { secretConfig.sep10SigningSeed } returns String(serverKP.secretSeed)
    every { appConfig.horizonUrl } returns mockHorizonUrl
    every { appConfig.stellarNetworkPassphrase } returns TESTNET.networkPassphrase
    val horizon = Horizon(appConfig)
    this.sep10Service = Sep10Service(appConfig, secretConfig, sep10Config, horizon, jwtService)

    // 3 ------ Run tests
    val validationRequest = ValidationRequest.of(transaction.toEnvelopeXdrBase64())
    assertDoesNotThrow { sep10Service.validateChallenge(validationRequest) }
  }

  @ParameterizedTest
  @CsvSource(value = ["true,test.client.stellar.org", "false,test.client.stellar.org", "false,"])
  @LockAndMockStatic([NetUtil::class, Sep10Challenge::class])
  fun `test create challenge ok`(clientAttributionRequired: Boolean, clientDomain: String?) {
    every { NetUtil.fetch(any()) } returns TEST_CLIENT_TOML

    every { sep10Config.isClientAttributionRequired } returns clientAttributionRequired
    every { sep10Config.allowedClientDomains } returns listOf(TEST_CLIENT_DOMAIN)
    val cr =
      ChallengeRequest.builder()
        .account(TEST_ACCOUNT)
        .memo(TEST_MEMO)
        .homeDomain(TEST_HOME_DOMAIN)
        .clientDomain(TEST_CLIENT_DOMAIN)
        .build()
    cr.clientDomain = clientDomain

    val challengeResponse = sep10Service.createChallenge(cr)

    assertEquals(challengeResponse.networkPassphrase, TESTNET.networkPassphrase)
    // TODO: This should be at most once but there is a concurrency bug in the test.
    verify(atLeast = 1, atMost = 2) {
      Sep10Challenge.newChallenge(
        any(),
        Network(TESTNET.networkPassphrase),
        TEST_ACCOUNT,
        TEST_HOME_DOMAIN,
        TEST_WEB_AUTH_DOMAIN,
        any(),
        clientDomain ?: "",
        any(),
        any()
      )
    }
  }

  @Test
  fun `test validate challenge when client account is on Stellar network`() {
    val vr = ValidationRequest()
    vr.transaction = createTestChallenge("", TEST_HOME_DOMAIN, false)

    val accountResponse = spyk(AccountResponse(clientKeyPair.accountId, 1))
    val signers =
      arrayOf(TestSigner(clientKeyPair.accountId, "ed25519_public_key", 1, "").toSigner())

    every { accountResponse.signers } returns signers
    every { accountResponse.thresholds.medThreshold } returns 1
    every { horizon.server.accounts().account(ofType(String::class)) } returns accountResponse

    val response = sep10Service.validateChallenge(vr)
    val jwt = jwtService.decode(response.token, Sep10Jwt::class.java)
    assertEquals("${clientKeyPair.accountId}:$TEST_MEMO", jwt.sub)
  }

  @Test
  @LockAndMockStatic([Sep10Challenge::class])
  fun `test validate challenge with client domain`() {
    val accountResponse = spyk(AccountResponse(clientKeyPair.accountId, 1))
    val signers =
      arrayOf(
        TestSigner(clientKeyPair.accountId, "ed25519_public_key", 1, "").toSigner(),
        TestSigner(clientDomainKeyPair.accountId, "ed25519_public_key", 1, "").toSigner()
      )

    every { accountResponse.signers } returns signers
    every { accountResponse.thresholds.medThreshold } returns 1
    every { horizon.server.accounts().account(ofType(String::class)) } returns accountResponse

    val vr = ValidationRequest()
    vr.transaction = createTestChallenge(TEST_CLIENT_DOMAIN, TEST_HOME_DOMAIN, true)

    val validationResponse = sep10Service.validateChallenge(vr)

    val token = jwtService.decode(validationResponse.token, Sep10Jwt::class.java)
    assertEquals(token.clientDomain, TEST_CLIENT_DOMAIN)
    assertEquals(token.homeDomain, TEST_HOME_DOMAIN)

    // Test when the transaction was not signed by the client domain and the client account exists
    vr.transaction = createTestChallenge(TEST_CLIENT_DOMAIN, TEST_HOME_DOMAIN, false)
    assertThrows<InvalidSep10ChallengeException> { sep10Service.validateChallenge(vr) }

    // Test when the transaction was not signed by the client domain and the client account not
    // exists
    every { horizon.server.accounts().account(ofType(String::class)) } answers
      {
        throw ErrorResponse(0, "mock error")
      }
    vr.transaction = createTestChallenge(TEST_CLIENT_DOMAIN, TEST_HOME_DOMAIN, false)

    assertThrows<InvalidSep10ChallengeException> { sep10Service.validateChallenge(vr) }
  }

  @Test
  fun `test validate challenge when client account is not on network`() {
    val vr = ValidationRequest()
    vr.transaction = createTestChallenge("", TEST_HOME_DOMAIN, false)

    every { horizon.server.accounts().account(ofType(String::class)) } answers
      {
        throw ErrorResponse(0, "mock error")
      }

    sep10Service.validateChallenge(vr)
  }

  @Suppress("CAST_NEVER_SUCCEEDS")
  @Test
  fun `Test validate challenge with bad request`() {
    assertThrows<SepValidationException> {
      sep10Service.validateChallenge(null as? ValidationRequest)
    }

    val vr = ValidationRequest()
    vr.transaction = null
    assertThrows<SepValidationException> { sep10Service.validateChallenge(vr) }
  }

  @Test
  fun `Test request to create challenge with bad home domain failure`() {
    val cr =
      ChallengeRequest.builder()
        .account(TEST_ACCOUNT)
        .memo(TEST_MEMO)
        .homeDomain(TEST_HOME_DOMAIN)
        .clientDomain(TEST_CLIENT_DOMAIN)
        .build()
    cr.homeDomain = "bad.homedomain.com"

    assertThrows<SepValidationException> { sep10Service.createChallenge(cr) }
  }

  @Test
  @LockAndMockStatic([NetUtil::class])
  fun `Test create challenge request with empty memo`() {
    every { NetUtil.fetch(any()) } returns TEST_CLIENT_TOML
    val cr =
      ChallengeRequest.builder()
        .account(TEST_ACCOUNT)
        .memo(null)
        .homeDomain(TEST_HOME_DOMAIN)
        .clientDomain(TEST_CLIENT_DOMAIN)
        .build()

    sep10Service.createChallenge(cr)
  }

  @Test
  fun `test when account is custodial, but the client domain is specified, exception should be thrown`() {
    every { sep10Config.knownCustodialAccountList } returns listOf(TEST_ACCOUNT)
    val cr =
      ChallengeRequest.builder()
        .account(TEST_ACCOUNT)
        .memo(null)
        .homeDomain(TEST_HOME_DOMAIN)
        .clientDomain(TEST_CLIENT_DOMAIN)
        .build()
    assertThrows<SepValidationException> { sep10Service.createChallenge(cr) }
  }

  @ParameterizedTest
  @MethodSource("homeDomains")
  fun `test client domain failures`(homeDomain: String?) {
    every { sep10Config.isClientAttributionRequired } returns true
    val cr =
      ChallengeRequest.builder()
        .account(TEST_ACCOUNT)
        .memo(TEST_MEMO)
        .homeDomain(TEST_HOME_DOMAIN)
        .clientDomain(TEST_CLIENT_DOMAIN)
        .build()
    cr.homeDomain = homeDomain
    cr.clientDomain = null

    assertThrows<SepValidationException> { sep10Service.createChallenge(cr) }

    // Test client domain rejection
    cr.clientDomain = TEST_CLIENT_DOMAIN
    assertThrows<SepNotAuthorizedException> { sep10Service.createChallenge(cr) }
  }

  @Test
  fun `test createChallenge() with bad account`() {
    every { sep10Config.isClientAttributionRequired } returns false
    val cr =
      ChallengeRequest.builder()
        .account(TEST_ACCOUNT)
        .memo(TEST_MEMO)
        .homeDomain(TEST_HOME_DOMAIN)
        .clientDomain(TEST_CLIENT_DOMAIN)
        .build()
    cr.account = "GXXX"

    assertThrows<SepValidationException> { sep10Service.createChallenge(cr) }
  }

  @ParameterizedTest
  @ValueSource(strings = ["ABC", "12AB", "-1", "0", Integer.MIN_VALUE.toString()])
  fun `test createChallenge() with bad memo`(badMemo: String) {
    every { sep10Config.isClientAttributionRequired } returns false
    val cr =
      ChallengeRequest.builder()
        .account(TEST_ACCOUNT)
        .memo(TEST_MEMO)
        .homeDomain(TEST_HOME_DOMAIN)
        .clientDomain(TEST_CLIENT_DOMAIN)
        .build()
    cr.account = TEST_ACCOUNT
    cr.memo = badMemo

    assertThrows<SepValidationException> { sep10Service.createChallenge(cr) }
  }

  @Test
  @LockAndMockStatic([Sep10Challenge::class])
  fun `test createChallengeResponse()`() {
    // Given
    sep10Service = spyk(sep10Service)
    // When
    sep10Service.createChallengeResponse(
      ChallengeRequest.builder()
        .account(TEST_ACCOUNT)
        .memo(TEST_MEMO)
        .homeDomain(TEST_HOME_DOMAIN)
        .clientDomain(null)
        .build(),
      MemoId(1234567890)
    )
    // Then
    verify(exactly = 0) { sep10Service.fetchSigningKeyFromClientDomain(any()) }

    // Given
    every { sep10Service.fetchSigningKeyFromClientDomain(any()) } returns clientKeyPair.accountId
    // When
    sep10Service.createChallengeResponse(
      ChallengeRequest.builder()
        .account(TEST_ACCOUNT)
        .memo(TEST_MEMO)
        .homeDomain(TEST_HOME_DOMAIN)
        .clientDomain(TEST_CLIENT_DOMAIN)
        .build(),
      MemoId(1234567890)
    )
    // Then
    verify(exactly = 1) { sep10Service.fetchSigningKeyFromClientDomain(TEST_CLIENT_DOMAIN) }

    // Given
    every { sep10Service.fetchSigningKeyFromClientDomain(any()) } throws IOException("mock error")
    InvalidSep10ChallengeException("mock error")
    // When
    val ioex =
      assertThrows<IOException> {
        sep10Service.createChallengeResponse(
          ChallengeRequest.builder()
            .account(TEST_ACCOUNT)
            .memo(TEST_MEMO)
            .homeDomain(TEST_HOME_DOMAIN)
            .clientDomain(TEST_CLIENT_DOMAIN)
            .build(),
          MemoId(1234567890)
        )
      }
    // Then
    assertEquals(ioex.message, "mock error")

    // Given
    every { sep10Service.fetchSigningKeyFromClientDomain(any()) } returns null
    every { sep10Service.newChallenge(any(), any(), any()) } throws
      InvalidSep10ChallengeException("mock error")
    // When
    val sepex =
      assertThrows<SepException> {
        sep10Service.createChallengeResponse(
          ChallengeRequest.builder()
            .account(TEST_ACCOUNT)
            .memo(TEST_MEMO)
            .homeDomain(TEST_HOME_DOMAIN)
            .clientDomain(TEST_CLIENT_DOMAIN)
            .build(),
          MemoId(1234567890)
        )
      }
    // Then
    assertTrue(sepex.message!!.startsWith("Failed to create the sep-10 challenge"))
  }

  @Test
  @LockAndMockStatic([NetUtil::class])
  fun `test getClientAccountId failure`() {
    every { NetUtil.fetch(any()) } returns
      "       NETWORK_PASSPHRASE=\"Public Global Stellar Network ; September 2015\"\n"

    assertThrows<SepException> { Sep10Helper.fetchSigningKeyFromClientDomain(TEST_CLIENT_DOMAIN) }

    every { NetUtil.fetch(any()) } answers { throw IOException("Cannot connect") }
    assertThrows<SepException> { Sep10Helper.fetchSigningKeyFromClientDomain(TEST_CLIENT_DOMAIN) }

    every { NetUtil.fetch(any()) } returns
      """
      NETWORK_PASSPHRASE="Public Global Stellar Network ; September 2015"
      HORIZON_URL="https://horizon.stellar.org"
      FEDERATION_SERVER="https://preview.lobstr.co/federation/"
      SIGNING_KEY="BADKEY"
      """
    assertThrows<SepException> { Sep10Helper.fetchSigningKeyFromClientDomain(TEST_CLIENT_DOMAIN) }
  }

  @Test
  @LockAndMockStatic([Sep10Challenge::class])
  fun `test createChallenge signing error`() {
    every { sep10Config.isClientAttributionRequired } returns false
    every {
      Sep10Challenge.newChallenge(any(), any(), any(), any(), any(), any(), any(), any(), any())
    } answers { throw InvalidSep10ChallengeException("mock exception") }

    assertThrows<SepException> {
      sep10Service.createChallenge(
        ChallengeRequest.builder()
          .account(TEST_ACCOUNT)
          .memo(TEST_MEMO)
          .homeDomain(TEST_HOME_DOMAIN)
          .clientDomain(TEST_CLIENT_DOMAIN)
          .build()
      )
    }
  }

  @Test
  @LockAndMockStatic([Sep10Challenge::class])
  fun `test createChallenge() ok`() {
    every { sep10Config.knownCustodialAccountList } returns listOf(TEST_ACCOUNT)
    val cr =
      ChallengeRequest.builder()
        .account(TEST_ACCOUNT)
        .memo(TEST_MEMO)
        .homeDomain(TEST_HOME_DOMAIN)
        .clientDomain(null)
        .build()

    assertDoesNotThrow { sep10Service.createChallenge(cr) }
    verify(exactly = 2) { sep10Config.knownCustodialAccountList }
  }

  @Test
  fun `Test createChallenge() when isKnownCustodialAccountRequired is not enabled`() {
    every { sep10Config.knownCustodialAccountList } returns
      listOf("G321E23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP")
    val cr =
      ChallengeRequest.builder()
        .account(TEST_ACCOUNT)
        .memo(TEST_MEMO)
        .homeDomain(TEST_HOME_DOMAIN)
        .clientDomain(null)
        .build()

    assertDoesNotThrow { sep10Service.createChallenge(cr) }
    verify(exactly = 2) { sep10Config.knownCustodialAccountList }
  }

  @Test
  fun `test the challenge with existent account, multisig, and client domain`() {
    // 1 ------ Create Test Transaction

    // serverKP does not exist in the network.
    val serverWebAuthDomain = TEST_WEB_AUTH_DOMAIN
    val serverHomeDomain = TEST_HOME_DOMAIN
    val serverKP = KeyPair.random()

    // clientDomainKP doesn't exist in the network. Refers to the walletAcc (like Lobstr's)
    val clientDomainKP = KeyPair.random()

    // Master account of the multisig. It'll be created in the network.
    val clientMasterKP = KeyPair.random()
    val clientAddress = clientMasterKP.accountId
    // Secondary account of the multisig. It'll be created in the network.
    val clientSecondaryKP = KeyPair.random()

    val nonce = ByteArray(48)
    val random = SecureRandom()
    random.nextBytes(nonce)
    val base64Encoding = BaseEncoding.base64()
    val encodedNonce = base64Encoding.encode(nonce).toByteArray()

    val sourceAccount = Account(serverKP.accountId, -1L)
    val op1DomainNameMandatory =
      ManageDataOperation.Builder("$serverHomeDomain auth", encodedNonce)
        .setSourceAccount(clientAddress)
        .build()
    val op2WebAuthDomainMandatory =
      ManageDataOperation.Builder("web_auth_domain", serverWebAuthDomain.toByteArray())
        .setSourceAccount(serverKP.accountId)
        .build()
    val op3clientDomainOptional =
      ManageDataOperation.Builder("client_domain", "lobstr.co".toByteArray())
        .setSourceAccount(clientDomainKP.accountId)
        .build()

    val transaction =
      TransactionBuilder(AccountConverter.enableMuxed(), sourceAccount, TESTNET)
        .addPreconditions(
          TransactionPreconditions.builder().timeBounds(TimeBounds.expiresAfter(900)).build()
        )
        .setBaseFee(100)
        .addOperation(op1DomainNameMandatory)
        .addOperation(op2WebAuthDomainMandatory)
        .addOperation(op3clientDomainOptional)
        .build()

    transaction.sign(serverKP)
    transaction.sign(clientDomainKP)
    transaction.sign(clientMasterKP)
    transaction.sign(clientSecondaryKP)

    // 2 ------ Create Services
    every { secretConfig.sep10SigningSeed } returns String(serverKP.secretSeed)
    every { appConfig.horizonUrl } returns "https://horizon-testnet.stellar.org"
    every { appConfig.stellarNetworkPassphrase } returns TESTNET.networkPassphrase
    val horizon = Horizon(appConfig)
    this.sep10Service = Sep10Service(appConfig, secretConfig, sep10Config, horizon, jwtService)

    // 3 ------ Setup multisig
    val httpRequest =
      Request.Builder()
        .url("https://horizon-testnet.stellar.org/friendbot?addr=" + clientMasterKP.accountId)
        .header("Content-Type", "application/json")
        .get()
        .build()
    val response = httpClient.newCall(httpRequest).execute()
    assertEquals(200, response.code)

    val clientAccount = horizon.server.accounts().account(clientMasterKP.accountId)
    val multisigTx =
      TransactionBuilder(AccountConverter.enableMuxed(), clientAccount, TESTNET)
        .addPreconditions(
          TransactionPreconditions.builder().timeBounds(TimeBounds.expiresAfter(900)).build()
        )
        .setBaseFee(300)
        .addOperation(
          SetOptionsOperation.Builder()
            .setLowThreshold(20)
            .setMediumThreshold(20)
            .setHighThreshold(20)
            .setSigner(Signer.ed25519PublicKey(clientSecondaryKP), 10)
            .setMasterKeyWeight(10)
            .build()
        )
        .build()
    multisigTx.sign(clientMasterKP)
    horizon.server.submitTransaction(multisigTx)

    // 4 ------ Run tests
    val validationRequest = ValidationRequest.of(transaction.toEnvelopeXdrBase64())
    assertDoesNotThrow { sep10Service.validateChallenge(validationRequest) }
  }
}
