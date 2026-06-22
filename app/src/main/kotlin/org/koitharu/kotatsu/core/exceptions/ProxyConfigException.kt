package org.koitharu.kotatsu.core.exceptions

import java.net.ProtocolException

class ProxyConfigException : ProtocolException("Wrong proxy configuration") {

	// Thrown per network request whenever a proxy is enabled but misconfigured. On a permanently
	// broken proxy this fires on every connection attempt and OkHttp retains the instances in its
	// suppressed-exception chains — a captured OOM heap held ~631k of them, ~375 MB of which was the
	// long[] stack backtraces alone. It's a control-flow signal, not a debugging aid, so skip the
	// (expensive to capture, expensive to retain) stack trace.
	override fun fillInStackTrace(): Throwable = this
}
