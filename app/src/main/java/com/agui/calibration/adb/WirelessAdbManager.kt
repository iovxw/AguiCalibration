package com.agui.calibration.adb

import android.content.Context
import android.os.Build
import android.util.Log
import com.agui.calibration.root.RootDaemonScriptInstaller
import com.agui.calibration.root.RootCalibrationProxy
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.AdbPairingRequiredException
import io.github.muntashirakon.adb.android.AdbMdns
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Security
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

private const val LOG_TAG = "WirelessAdbManager"

object WirelessAdbManager {

	data class Endpoint(
		val host: String,
		val port: Int
	)

	data class OperationResult(
		val success: Boolean,
		val message: String,
		val details: String = ""
	)

	private const val LOCAL_HOST = "127.0.0.1"
	private const val CONNECT_TIMEOUT_MS = 5_000L
	private const val ROOT_RECONNECT_DELAY_MS = 1_500L

	fun discoverPairingEndpoint(context: Context, timeoutMillis: Long = CONNECT_TIMEOUT_MS): Endpoint? {
		return discoverEndpoint(context, AdbMdns.SERVICE_TYPE_TLS_PAIRING, timeoutMillis)
	}

	fun authorizeAndStartDaemon(
		context: Context,
		pairingHost: String,
		pairingCode: String,
		pairingPort: Int,
		packageCodePath: String,
		rootProxy: RootCalibrationProxy
	): OperationResult {
		val normalizedHost = pairingHost.trim().ifEmpty { LOCAL_HOST }
		val normalizedCode = pairingCode.trim()
		if (normalizedCode.length != 6 || normalizedCode.any { !it.isDigit() }) {
			return OperationResult(false, "授权码格式无效", "pairing_code_invalid=$pairingCode")
		}
		if (pairingPort <= 0) {
			return OperationResult(false, "配对端口无效", "pairing_port_invalid=$pairingPort")
		}

		val manager = try {
			RootDaemonScriptInstaller.ensureInstalled(context)
			AppAdbConnectionManager.getInstance(context)
		} catch (t: Throwable) {
			return OperationResult(false, "初始化 ADB 客户端失败", t.stackTraceToString())
		}

		val details = mutableListOf<String>()
		return try {
			manager.pair(normalizedHost, pairingPort, normalizedCode)
			details += "pair success host=$normalizedHost port=$pairingPort"
			connectRootAndStartDaemon(context, packageCodePath, rootProxy, manager, details)
		} catch (t: Throwable) {
			OperationResult(false, "无线调试配对失败", (details + t.stackTraceToString()).joinToString("\n"))
		}
	}

	fun connectRootAndStartDaemon(
		context: Context,
		packageCodePath: String,
		rootProxy: RootCalibrationProxy
	): OperationResult {
		val manager = try {
			RootDaemonScriptInstaller.ensureInstalled(context)
			AppAdbConnectionManager.getInstance(context)
		} catch (t: Throwable) {
			return OperationResult(false, "初始化 ADB 客户端失败", t.stackTraceToString())
		}
		return connectRootAndStartDaemon(context, packageCodePath, rootProxy, manager, mutableListOf())
	}

	private fun connectRootAndStartDaemon(
		context: Context,
		packageCodePath: String,
		rootProxy: RootCalibrationProxy,
		manager: AppAdbConnectionManager,
		details: MutableList<String>
	): OperationResult {
		return try {
			if (!ensureConnected(manager, context)) {
				return OperationResult(false, "未能连接无线调试", details.joinToString("\n"))
			}
			details += "adb connected"

			val rootResponse = try {
				readDestination(manager, "root:")
			} catch (t: Throwable) {
				details += "root request transient failure=${t.javaClass.simpleName}:${t.message}"
				""
			}
			if (rootResponse.isNotBlank()) {
				details += "root response=$rootResponse"
			}

			Thread.sleep(ROOT_RECONNECT_DELAY_MS)
			safeDisconnect(manager)

			if (!ensureConnected(manager, context)) {
				return OperationResult(false, "请求 root 后无法重新连接无线调试", details.joinToString("\n"))
			}

			val uid = runShell(manager, "id -u")
				.lineSequence()
				.map { it.trim() }
				.firstOrNull { it.isNotEmpty() }
				.orEmpty()
			details += "shell uid=$uid"
			if (uid != "0") {
				return OperationResult(false, "无线调试已连接，但 adbd 未切换到 root", details.joinToString("\n"))
			}

			val probe = rootProxy.probe()
			if (probe.exitCode == 0 && probe.stdout.contains("OK service_found")) {
				details += "root daemon already running"
				return OperationResult(true, "无线 ADB 已授权，root daemon 已就绪", details.joinToString("\n"))
			}

			val startOutput = runShell(manager, daemonStartCommand(context))
			if (startOutput.isNotBlank()) {
				details += "daemon start=$startOutput"
			}

			repeat(6) {
				Thread.sleep(500)
				val daemonProbe = rootProxy.probe()
				if (daemonProbe.exitCode == 0 && daemonProbe.stdout.contains("OK service_found")) {
					details += "daemon probe success"
					return OperationResult(true, "无线 ADB 已授权，root daemon 启动成功", details.joinToString("\n"))
				}
				if (daemonProbe.combined.isNotBlank()) {
					details += "daemon probe retry=${daemonProbe.combined}"
				}
			}

			OperationResult(false, "无线 ADB 已授权，但 root daemon 启动后不可访问", details.joinToString("\n"))
		} catch (pairingRequired: AdbPairingRequiredException) {
			OperationResult(false, "当前无线调试尚未配对", (details + pairingRequired.stackTraceToString()).joinToString("\n"))
		} catch (t: Throwable) {
			OperationResult(false, "无线 ADB 启动流程失败", (details + t.stackTraceToString()).joinToString("\n"))
		}
	}

	private fun ensureConnected(manager: AppAdbConnectionManager, context: Context): Boolean {
		if (manager.isConnected) {
			return true
		}
		return try {
			manager.autoConnect(context.applicationContext, CONNECT_TIMEOUT_MS)
		} catch (_: AdbPairingRequiredException) {
			false
		}
	}

	private fun runShell(manager: AppAdbConnectionManager, command: String): String {
		return readDestination(manager, "shell:$command")
	}

	private fun readDestination(manager: AppAdbConnectionManager, destination: String): String {
		return manager.openStream(destination).use { stream ->
			stream.openInputStream().readTextCompat()
		}.trim()
	}

	private fun discoverEndpoint(context: Context, serviceType: String, timeoutMillis: Long): Endpoint? {
		val host = AtomicReference<String?>(null)
		val port = AtomicInteger(-1)
		val latch = CountDownLatch(1)
		val mdns = AdbMdns(context.applicationContext, serviceType) { hostAddress, discoveredPort ->
			if (hostAddress != null && discoveredPort > 0) {
				host.set(hostAddress.hostAddress ?: LOCAL_HOST)
				port.set(discoveredPort)
				latch.countDown()
			}
		}
		mdns.start()
		return try {
			if (latch.await(timeoutMillis, TimeUnit.MILLISECONDS)) {
				Endpoint(host.get() ?: LOCAL_HOST, port.get())
			} else {
				null
			}
		} finally {
			mdns.stop()
		}
	}

	private fun daemonStartCommand(context: Context): String {
		val escapedScriptPath = shellQuote(RootDaemonScriptInstaller.scriptPath(context))
		return "nohup sh $escapedScriptPath </dev/null >/data/local/tmp/agui_calibrationd.log 2>&1 & echo daemon_start_requested"
	}

	private fun shellQuote(value: String): String {
		return "'${value.replace("'", "'\"'\"'")}'"
	}

	private fun safeDisconnect(manager: AppAdbConnectionManager) {
		runCatching { manager.disconnect() }
	}
}

private class AppAdbConnectionManager private constructor(
	private val appContext: Context
) : AbsAdbConnectionManager() {

	private val privateKey: PrivateKey
	private val certificate: Certificate

	init {
		ensureBouncyCastleProvider()
		setApi(Build.VERSION.SDK_INT)
		val storageDir = File(appContext.filesDir, "wireless_adb")
		if (!storageDir.exists()) {
			storageDir.mkdirs()
		}

		val privateKeyFile = File(storageDir, "private.pk8")
		val certificateFile = File(storageDir, "cert.der")

		val credentials = runCatching {
			loadOrGenerateCredentials(privateKeyFile, certificateFile)
		}.recoverCatching { firstError ->
			Log.w(LOG_TAG, "credential init failed once, wiping cached adb credentials", firstError)
			privateKeyFile.delete()
			certificateFile.delete()
			loadOrGenerateCredentials(privateKeyFile, certificateFile)
		}.getOrElse { fatalError ->
			Log.e(LOG_TAG, "credential init failed after retry", fatalError)
			throw fatalError
		}
		privateKey = credentials.first
		certificate = credentials.second
	}

	override fun getPrivateKey(): PrivateKey = privateKey

	override fun getCertificate(): Certificate = certificate

	override fun getDeviceName(): String = "AguiCalibration"

	companion object {
		@Volatile
		private var instance: AppAdbConnectionManager? = null

		fun getInstance(context: Context): AppAdbConnectionManager {
			return instance ?: synchronized(this) {
				instance ?: AppAdbConnectionManager(context.applicationContext).also { instance = it }
			}
		}

		private fun ensureBouncyCastleProvider() {
			if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
				Security.addProvider(BouncyCastleProvider())
			}
		}

		private fun generateKeyPair(): java.security.KeyPair {
			val generator = KeyPairGenerator.getInstance("RSA")
			generator.initialize(2048)
			return generator.generateKeyPair()
		}

		private fun loadOrGenerateCredentials(
			privateKeyFile: File,
			certificateFile: File
		): Pair<PrivateKey, Certificate> {
			val loadedPrivateKey = readPrivateKey(privateKeyFile)
			val loadedCertificate = readCertificate(certificateFile)
			if (loadedPrivateKey != null && loadedCertificate != null) {
				return loadedPrivateKey to loadedCertificate
			}

			val generatedKeyPair = generateKeyPair()
			val generatedCertificate = generateCertificate(generatedKeyPair.public, generatedKeyPair.private)
			writePrivateKey(privateKeyFile, generatedKeyPair.private)
			writeCertificate(certificateFile, generatedCertificate)
			return generatedKeyPair.private to generatedCertificate
		}

		private fun generateCertificate(publicKey: PublicKey, privateKey: PrivateKey): X509Certificate {
			val subject = X500Name("CN=AguiCalibration")
			val notBefore = Date(System.currentTimeMillis() - 60_000)
			val notAfter = Date(System.currentTimeMillis() + 3650L * 24L * 60L * 60L * 1000L)
			val serial = BigInteger.valueOf(System.currentTimeMillis())
			val builder = JcaX509v3CertificateBuilder(
				subject,
				serial,
				notBefore,
				notAfter,
				subject,
				publicKey
			)
			val signer = JcaContentSignerBuilder("SHA256withRSA")
				.build(privateKey)
			return JcaX509CertificateConverter()
				.getCertificate(builder.build(signer))
		}

		private fun readPrivateKey(file: File): PrivateKey? {
			if (!file.exists()) {
				return null
			}
			return runCatching {
				val keySpec = PKCS8EncodedKeySpec(file.readBytes())
				KeyFactory.getInstance("RSA").generatePrivate(keySpec)
			}.getOrElse {
				Log.w(LOG_TAG, "failed to read cached private key", it)
				null
			}
		}

		private fun readCertificate(file: File): Certificate? {
			if (!file.exists()) {
				return null
			}
			return runCatching {
				FileInputStream(file).use { stream ->
					CertificateFactory.getInstance("X.509").generateCertificate(stream)
				}
			}.getOrElse {
				Log.w(LOG_TAG, "failed to read cached certificate", it)
				null
			}
		}

		private fun writePrivateKey(file: File, privateKey: PrivateKey) {
			FileOutputStream(file).use { output ->
				output.write(privateKey.encoded)
			}
		}

		private fun writeCertificate(file: File, certificate: Certificate) {
			FileOutputStream(file).use { output ->
				output.write(certificate.encoded)
			}
		}
	}
}

private fun InputStream.readTextCompat(): String {
	return bufferedReader().use { it.readText() }
}
