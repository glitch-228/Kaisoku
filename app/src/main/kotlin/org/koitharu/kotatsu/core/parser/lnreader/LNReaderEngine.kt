/*
 * Copyright 2026 Kototoro contributors (https://github.com/skepsun/kototoro)
 * Licensed under Apache License, Version 2.0. Ported to Kaisoku for
 * LNReader plugin support.
 */
package org.koitharu.kotatsu.core.parser.lnreader

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.FunctionBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jsoup.Jsoup

/**
 * QuickJS-based JavaScript engine for executing LNReader plugins.
 * Manages a QuickJS context with injected native bridges (fetch, cheerio, console).
 */
class LNReaderEngine(
	private val fetchBridge: LNReaderFetchBridge,
	private val storage: LNReaderStorage = LNReaderStorage(),
) {
	companion object {
		private const val TAG = "LNReaderEngine"
		private const val MAX_STACK_SIZE = 1L shl 20   // 1MB
		private const val MEMORY_LIMIT = 64L shl 20    // 64MB
	}

	private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }

	/**
	 * Load a LNReader plugin and set up its execution environment.
	 * Returns a configured QuickJs instance ready to call plugin methods.
	 * The caller must close the returned instance.
	 */
	suspend fun createPluginContext(jsCode: String, pluginId: String): QuickJs {
		val qjs = QuickJs.create(jobDispatcher = Dispatchers.Default)
		qjs.maxStackSize = MAX_STACK_SIZE
		qjs.memoryLimit = MEMORY_LIMIT

		try {
			registerFetchBridge(qjs)
			registerConsole(qjs)
			registerGlobalPolyfills(qjs)
			registerDomBridge(qjs)
			setupModuleSystem(qjs)
			qjs.evaluate<Any?>(jsCode, "<lnreader-plugin>")

			val sanitizedId = pluginId.replace(Regex("[^a-zA-Z0-9_]"), "_")
			qjs.evaluate<Any?>(
				"""
				(function() {
					var plugin = (typeof exports !== 'undefined' && exports.default) ||
					             (typeof exports !== 'undefined' && exports) ||
					             (typeof module !== 'undefined' && module.exports && module.exports.default) ||
					             (typeof module !== 'undefined' && module.exports);
					if (plugin && typeof plugin === 'function') {
						plugin = new plugin();
					}
					globalThis.__plugin_${sanitizedId} = plugin;
					var methods = [];
					for (var p = plugin; p && p !== Object.prototype; p = Object.getPrototypeOf(p)) {
						methods = methods.concat(Object.getOwnPropertyNames(p));
					}
					console.log('Plugin ' + '$pluginId' + ' exports: ' + methods.join(', '));
				})();
				""".trimIndent(),
				"<plugin-init>"
			)

			LnLog.d(TAG, "Plugin $pluginId loaded successfully")
			return qjs
		} catch (e: Exception) {
			qjs.close()
			if (e is kotlinx.coroutines.CancellationException) throw e
			LnLog.e(TAG, "Failed to load plugin $pluginId", e)
			throw LNReaderJSException("Failed to load plugin $pluginId: ${e.message}", e)
		}
	}

	private suspend fun registerFetchBridge(qjs: QuickJs) {
		qjs.defineBinding("__nativeFetch", FunctionBinding<String?> { args ->
			val url = args.getOrNull(0) as? String ?: return@FunctionBinding null
			val init = args.getOrNull(1) as? String
			fetchBridge.fetch(url, init)
		})

		qjs.evaluate<Any?>(
			"""
			var __fetchBridgeResults = {};
			var __fetchBridgeNextId = 0;
			""".trimIndent(),
			"<fetch-init>"
		)

		val fetchScript = fetchBridge.toJavaScriptFunction()
		qjs.evaluate<Any?>(fetchScript, "<fetch-bridge>")
	}

	private suspend fun registerConsole(qjs: QuickJs) {
		qjs.defineBinding("__nativeConsole", FunctionBinding<Any?> { args ->
			val level = args.getOrNull(0) as? String ?: "log"
			val msg = args.drop(1).joinToString(" ") { it.toString() }
			when (level) {
				"error" -> LnLog.e(TAG, "[JS] $msg")
				"warn" -> LnLog.w(TAG, "[JS] $msg")
				else -> LnLog.d(TAG, "[JS] $msg")
			}
		})

		qjs.evaluate<Any?>(
			"""
			var console = {
				log: function(...args) { __nativeConsole('log', ...args); },
				warn: function(...args) { __nativeConsole('warn', ...args); },
				error: function(...args) { __nativeConsole('error', ...args); },
				info: function(...args) { __nativeConsole('info', ...args); },
				debug: function(...args) { __nativeConsole('debug', ...args); }
			};
			if (!String.prototype.replaceAll) {
				String.prototype.replaceAll = function(str, newStr) {
					if (Object.prototype.toString.call(str).toLowerCase() === '[object regexp]') {
						return this.replace(str, newStr);
					}
					var escapeRegex = function(s) {
					    return s.replace(/[.*+?^${'$'}()|[\]\\]/g, '\\$&');
					};
					return this.replace(new RegExp(escapeRegex(str), 'g'), newStr);
				};
			}
			""".trimIndent(),
			"<console>"
		)
	}

	private suspend fun registerGlobalPolyfills(qjs: QuickJs) {
		qjs.evaluate<Any?>(globalPolyfillsScript(), "<polyfills>")
	}

	internal fun globalPolyfillsScript(): String = """
			// Setup URL API polyfill with comprehensive error handling
			globalThis.URL = function(url, base) {
				if (url === null || url === undefined) throw new Error('Invalid URL');
				if (typeof url === 'object' && url.href) url = url.href;
				url = String(url);
				let fullUrl = url;
				if (base && !url.match(/^[a-zA-Z]+:\/\//)) {
					base = String(base);
					if (url.startsWith('/')) {
						const baseMatch = base.match(/^(https?:\/\/[^\/]+)/);
						fullUrl = baseMatch ? baseMatch[1] + url : url;
					} else if (url.startsWith('?')) {
						const baseMatch = base.match(/^([^?#]+)/);
						fullUrl = baseMatch ? baseMatch[1] + url : url;
					} else if (url.startsWith('#')) {
						const baseMatch = base.match(/^([^#]+)/);
						fullUrl = baseMatch ? baseMatch[1] + url : url;
					} else {
						const match = base.match(/^(https?:\/\/[^\/]+)(.*)${'$'}/);
						if (match) {
							const origin = match[1];
							let path = match[2] || '/';
							if (path.indexOf('?') >= 0) path = path.substring(0, path.indexOf('?'));
							if (path.indexOf('#') >= 0) path = path.substring(0, path.indexOf('#'));
							path = path.substring(0, path.lastIndexOf('/') + 1);
							fullUrl = origin + path + url;
						} else {
							fullUrl = base.endsWith('/') ? base + url : base + '/' + url;
						}
					}
				}
				// Resolve dot segments (. and ..)
				let urlMatch = fullUrl.match(/^(https?:\/\/[^\/]+)(.*)${'$'}/);
				if (urlMatch) {
					let origin = urlMatch[1];
					let pathAndRest = urlMatch[2] || '/';
					let queryHash = '';
					let qIdx = pathAndRest.indexOf('?');
					let hIdx = pathAndRest.indexOf('#');
					let splitIdx = qIdx >= 0 ? qIdx : (hIdx >= 0 ? hIdx : -1);
					if (splitIdx >= 0) {
						queryHash = pathAndRest.substring(splitIdx);
						pathAndRest = pathAndRest.substring(0, splitIdx);
					}
					let segments = pathAndRest.split('/');
					let resolved = [];
					for (let seg of segments) {
						if (seg === '.') continue;
						if (seg === '..') {
							if (resolved.length > 0 && resolved[resolved.length - 1] !== '') {
								resolved.pop();
							}
						} else {
							resolved.push(seg);
						}
					}
					fullUrl = origin + resolved.join('/') + queryHash;
				}
				const match = fullUrl.match(/^(https?):\/\/([^/?#]+)(\/[^?#]*)?(\\?[^#]*)?(#.*)?${'$'}/);
				if (!match) throw new Error('Invalid URL: ' + fullUrl);

				const protocol = match[1] || 'http';
				const hostWithPort = match[2] || '';
				const pathname = match[3] || '/';
				const search = match[4] || '';
				const hash = match[5] || '';

				const hostParts = (hostWithPort || '').split(':');
				this.protocol = String(protocol) + ':';
				this.host = String(hostWithPort);
				this.hostname = String(hostParts[0] || '');
				this.port = String(hostParts[1] || '');
				this.pathname = String(pathname);
				this.search = String(search);
				this.hash = String(hash);
				this.href = String(fullUrl);
				this.origin = String(protocol) + '://' + String(hostWithPort);
				this.toString = function() { return this.href; };
				this.toJSON = function() { return this.href; };
			};

			// Setup URLSearchParams
			globalThis.URLSearchParams = function(init) {
				this.params = {};
				if (typeof init === 'string') {
					const query = init.startsWith('?') ? init.substring(1) : init;
					if (query) {
						query.split('&').forEach(function(pair) {
							const parts = pair.split('=');
							const key = decodeURIComponent(parts[0]);
							const value = parts[1] ? decodeURIComponent(parts[1]) : '';
							if (!this.params[key]) this.params[key] = [];
							this.params[key].push(value);
						}.bind(this));
					}
				} else if (init && typeof init === 'object') {
					for (const key in init) {
						if (init.hasOwnProperty(key)) this.params[key] = [String(init[key])];
					}
				}
				this.append = function(key, value) {
					if (!this.params[key]) this.params[key] = [];
					this.params[key].push(String(value));
				};
				this.delete = function(key) { delete this.params[key]; };
				this.get = function(key) { return this.params[key] ? this.params[key][0] : null; };
				this.getAll = function(key) { return this.params[key] || []; };
				this.has = function(key) { return key in this.params; };
				this.set = function(key, value) { this.params[key] = [String(value)]; };
				this.toString = function() {
					const parts = [];
					for (const key in this.params) {
						if (this.params.hasOwnProperty(key)) {
							this.params[key].forEach(function(value) {
								parts.push(encodeURIComponent(key) + '=' + encodeURIComponent(value));
							});
						}
					}
					return parts.join('&');
				};
				this.entries = function() {
					const entries = [];
					for (const key in this.params) {
						if (this.params.hasOwnProperty(key)) {
							this.params[key].forEach(function(value) { entries.push([key, value]); });
						}
					}
					return entries;
				};
				this.keys = function() { return Object.keys(this.params); };
				this.values = function() {
					const values = [];
					for (const key in this.params) {
						if (this.params.hasOwnProperty(key)) values.push(...this.params[key]);
					}
					return values;
				};
			};

			if (typeof globalThis.atob === 'undefined') {
				globalThis.atob = function(str) {
					const chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=';
					let output = '';
					str = String(str).replace(/=+${'$'}/, '');
					if (str.length % 4 === 1) throw new Error('Invalid base64 string');
					for (let i = 0; i < str.length;) {
						const enc1 = chars.indexOf(str.charAt(i++));
						const enc2 = chars.indexOf(str.charAt(i++));
						const enc3 = chars.indexOf(str.charAt(i++));
						const enc4 = chars.indexOf(str.charAt(i++));
						const chr1 = (enc1 << 2) | (enc2 >> 4);
						const chr2 = ((enc2 & 15) << 4) | (enc3 >> 2);
						const chr3 = ((enc3 & 3) << 6) | enc4;
						output += String.fromCharCode(chr1);
						if (enc3 !== 64 && enc3 !== -1) output += String.fromCharCode(chr2);
						if (enc4 !== 64 && enc4 !== -1) output += String.fromCharCode(chr3);
					}
					return output;
				};
			}

			if (typeof globalThis.btoa === 'undefined') {
				globalThis.btoa = function(str) {
					const chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=';
					let output = '';
					str = String(str);
					for (let i = 0; i < str.length;) {
						const chr1 = str.charCodeAt(i++);
						const chr2 = str.charCodeAt(i++);
						const chr3 = str.charCodeAt(i++);
						const enc1 = chr1 >> 2;
						const enc2 = ((chr1 & 3) << 4) | (chr2 >> 4);
						let enc3 = ((chr2 & 15) << 2) | (chr3 >> 6);
						let enc4 = chr3 & 63;
						if (isNaN(chr2)) { enc3 = enc4 = 64; } else if (isNaN(chr3)) { enc4 = 64; }
						output += chars.charAt(enc1) + chars.charAt(enc2) + chars.charAt(enc3) + chars.charAt(enc4);
					}
					return output;
				};
			}

			if (typeof globalThis.TextEncoder === 'undefined') {
				globalThis.TextEncoder = function() {
					this.encode = function(str) {
						const utf8 = unescape(encodeURIComponent(str));
						const result = new Uint8Array(utf8.length);
						for (let i = 0; i < utf8.length; i++) result[i] = utf8.charCodeAt(i);
						return result;
					};
				};
			}

			if (typeof globalThis.TextDecoder === 'undefined') {
				globalThis.TextDecoder = function() {
					this.decode = function(buffer) {
						const bytes = new Uint8Array(buffer);
						let str = '';
						for (let i = 0; i < bytes.length; i++) str += String.fromCharCode(bytes[i]);
						return decodeURIComponent(escape(str));
					};
				};
			}

			if (typeof globalThis.Uint8Array === 'undefined') {
				globalThis.Uint8Array = function(length) {
					const arr = new Array(length);
					for (let i = 0; i < length; i++) arr[i] = 0;
					arr.buffer = new ArrayBuffer(length);
					arr.byteLength = length;
					return arr;
				};
			}

			if (typeof globalThis.ArrayBuffer === 'undefined') {
				globalThis.ArrayBuffer = function(length) { this.byteLength = length || 0; };
			}

			globalThis.Blob = function(parts, options) {
				this.parts = parts || [];
				this.options = options || {};
				this.size = this.parts.reduce(function(acc, part) { return acc + (part.length || 0); }, 0);
				this.type = this.options.type || '';
			};

			globalThis.FormData = function() {
				this.data = {};
				this.append = function(key, value) { if (!this.data[key]) this.data[key] = []; this.data[key].push(value); };
				this.get = function(key) { return this.data[key] ? this.data[key][0] : null; };
				this.getAll = function(key) { return this.data[key] || []; };
				this.has = function(key) { return key in this.data; };
				this.delete = function(key) { delete this.data[key]; };
				this.set = function(key, value) { this.data[key] = [value]; };
				this.entries = function() {
					const entries = [];
					for (const key in this.data) { for (const value of this.data[key]) entries.push([key, value]); }
					return entries;
				};
			};

			// Comprehensive Date polyfill for QuickJS.
			// QuickJS throws "Date value is NaN" from both the constructor (new Date("invalid"))
			// AND from prototype methods (date.toISOString()). We must patch both.
			(function() {
				var _RealDate = Date;

				// 1. Wrap constructor to catch throws on invalid date strings
				function SafeDate() {
					var d;
					try {
						if (arguments.length === 0) d = new _RealDate();
						else if (arguments.length === 1) {
							var arg = arguments[0];
							if (typeof arg === 'string') {
								// Try numeric timestamp first
								var num = Number(arg);
								if (!isNaN(num)) {
									d = new _RealDate(num);
								} else {
									// Try ISO-like format: replace common separators
									try { d = new _RealDate(arg); }
									catch(e2) { d = new _RealDate(0); }
								}
							} else {
								d = new _RealDate(arg);
							}
						}
						else if (arguments.length === 2) d = new _RealDate(arguments[0], arguments[1]);
						else if (arguments.length === 3) d = new _RealDate(arguments[0], arguments[1], arguments[2]);
						else d = new _RealDate(arguments[0], arguments[1], arguments[2], arguments[3], arguments[4], arguments[5], arguments[6]);
					} catch(e) {
						d = new _RealDate(0);
					}
					return d;
				}
				SafeDate.now = function() { return _RealDate.now(); };
				SafeDate.parse = function(s) { try { return _RealDate.parse(s); } catch(e) { return NaN; } };
				SafeDate.UTC = function() { try { return _RealDate.UTC.apply(_RealDate, arguments); } catch(e) { return NaN; } };
				SafeDate.prototype = _RealDate.prototype;
				globalThis.Date = SafeDate;

				// 2. Patch prototype methods to catch NaN-related throws
				var strMethods = ['toString', 'toISOString', 'toUTCString', 'toDateString',
					'toTimeString', 'toLocaleDateString', 'toLocaleTimeString', 'toLocaleString', 'toJSON',
					'toGMTString'];
				strMethods.forEach(function(method) {
					var orig = _RealDate.prototype[method];
					if (orig) {
						_RealDate.prototype[method] = function() {
							try { return orig.apply(this, arguments); }
							catch(e) { return ''; }
						};
					}
				});
				var numMethods = ['getTime', 'valueOf', 'getFullYear', 'getMonth', 'getDate',
					'getHours', 'getMinutes', 'getSeconds', 'getMilliseconds',
					'getUTCFullYear', 'getUTCMonth', 'getUTCDate', 'getUTCHours',
					'getUTCMinutes', 'getUTCSeconds', 'getUTCMilliseconds',
					'getTimezoneOffset', 'getDay', 'getUTCDay'];
				numMethods.forEach(function(method) {
					var orig = _RealDate.prototype[method];
					if (orig) {
						_RealDate.prototype[method] = function() {
							try { return orig.apply(this, arguments); }
							catch(e) { return NaN; }
						};
					}
				});
			})();

			globalThis.window = globalThis;
			globalThis.location = {
				href: 'about:blank', protocol: 'about:', host: 'blank', hostname: 'blank',
				port: '', pathname: '/blank', search: '', hash: '', origin: 'about:blank',
				toString: function() { return this.href; }
			};
			globalThis.document = {
				location: globalThis.location, URL: 'about:blank', domain: 'blank', referrer: '',
				title: '', cookie: '', documentURI: 'about:blank', baseURI: 'about:blank'
			};
			""".trimIndent()

	private suspend fun setupModuleSystem(qjs: QuickJs) {
		qjs.evaluate<Any?>(resource("cheerio.js"), "<cheerio>")
		qjs.evaluate<Any?>(resource("dayjs.min.js"), "<dayjs>")
		qjs.evaluate<Any?>(resource("localizedFormat.js"), "<dayjs-localized-format>")
		qjs.evaluate<Any?>("dayjs.extend(dayjs_plugin_localizedFormat);", "<dayjs-setup>")
		qjs.defineBinding("__nativeStorage", FunctionBinding<String?> { args ->
			val key = args.getOrNull(1) as? String ?: return@FunctionBinding null
			when (args.firstOrNull()) {
				"keys" -> org.json.JSONArray(storage.keys().toList()).toString()
				"get" -> storage.get(key)
				"set" -> { storage.set(key, args.getOrNull(2) as? String); null }
				else -> { storage.set(key, null); null }
			}
		})
		qjs.evaluate<Any?>(storageScript(), "<plugin-runtime>")
		qjs.evaluate<Any?>(moduleScript(), "<module-stubs>")
	}

	internal fun storageScript(): String = """
			function persistentStorage(prefix) {
				return {
					get: function(key, raw) {
						var value = __nativeStorage('get', prefix + String(key));
						if (value == null) return undefined;
						var item = JSON.parse(value);
						if (item.expires && Date.now() > item.expires) { this.delete(key); return undefined; }
						item.created = new Date(item.created);
						return raw ? item : item.value;
					},
					set: function(key, value, expires) {
						__nativeStorage('set', prefix + String(key), JSON.stringify({created: Date.now(), value: value,
							expires: expires instanceof Date ? expires.getTime() : expires}));
					},
					delete: function(key) { __nativeStorage('delete', prefix + String(key)); },
					getAllKeys: function() { return JSON.parse(__nativeStorage('keys', '')).filter(k => k.startsWith(prefix)).map(k => k.slice(prefix.length)); },
					clearAll: function() { this.getAllKeys().forEach(key => this.delete(key)); }
				};
			}
			globalThis.__pluginStorage = persistentStorage('plugin:');
			globalThis.__webStorage = {get: function() { return JSON.parse(__nativeStorage('get', 'web:local') || '{}'); }};
			globalThis.__sessionStorage = {get: function() { return {}; }};
			globalThis.Intl = {DateTimeFormat: function() {
				return {resolvedOptions: function() { return {timeZone: ${json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(java.util.TimeZone.getDefault().id))}}; }};
			}};
		""".trimIndent()

	internal fun moduleScript(): String = """
			globalThis.__cheerioIdCounter = 0;
			globalThis.__cheerioQueue = [];
			globalThis.__cheerioResults = {};


			globalThis.htmlparser2 = ${getHtmlParser2Library()};

			globalThis.__libs_novelStatus = ${getNovelStatusLibrary()};
			globalThis.__libs_filterInputs = ${getFilterInputsLibrary()};

			// Module stubs for LNReader plugin imports
			if (typeof globalThis.require === 'undefined') {
				globalThis.require = function(name) {
					console.log('REQUIRE:', name);
					if (name === '@libs/fetch') return {
						fetchApi: function(url, options) { return globalThis.fetch(url, options); },
						fetchText: function(url, options) { return globalThis.fetch(url, options).then(function(res) { return res.text(); }); },
						fetchFile: function(url) { return globalThis.fetch(url).then(function(res) { return res.text(); }); }
					};
					if (name === '@libs/novelStatus') return globalThis.__libs_novelStatus;
					if (name === '@libs/filterInputs') return globalThis.__libs_filterInputs;

					if (name === '@libs/storage' || name === '@lib/storage') return {
						storage: __pluginStorage, localStorage: __webStorage, sessionStorage: __sessionStorage
					};
					if (name === 'dayjs') return globalThis.dayjs;
					if (name === '@libs/defaultCover') return { defaultCover: '' };
					if (name === '@libs/isAbsoluteUrl') return {
						isUrlAbsolute: function(url) {
							if (!url) return false;
							return /^https?:\/\//i.test(url);
						}
					};
					if (name === '@libs/isUrlAbsolute') return {
						isUrlAbsolute: function(url) {
							if (!url) return false;
							return /^https?:\/\//i.test(url);
						}
					};

					if (name === 'htmlparser2') return globalThis.htmlparser2;
					if (name === 'cheerio') return globalThis.cheerio;

					throw new Error('Unsupported LNReader module: ' + name);
				};
			}
			// CommonJS module support
			if (typeof globalThis.exports === 'undefined') {
				globalThis.exports = {};
			}
			if (typeof globalThis.module === 'undefined') {
				globalThis.module = { exports: globalThis.exports };
			}
			// Timers polyfill
			if (typeof globalThis.setTimeout === 'undefined') {
				globalThis.setTimeout = function(fn) { fn(); return 1; };
				globalThis.clearTimeout = function() {};
				globalThis.setInterval = function(fn) { fn(); return 1; };
				globalThis.clearInterval = function() {};
			}
			""".trimIndent()
	private fun registerDomBridge(qjs: QuickJs) {
		val dom = LNReaderDomBridge()
		qjs.defineBinding("__nativeDom", FunctionBinding<String> { args ->
			dom.call(args[0] as String, args[1] as String)
		})
	}

	internal fun resource(name: String): String = checkNotNull(javaClass.getResourceAsStream("/lnreader/$name")) {
		"Missing LNReader runtime resource: $name"
	}.bufferedReader().use { it.readText() }

	private fun getHtmlParser2Library(): String {
		return """
			(function() {
				const voidElements = new Set(['area', 'base', 'br', 'col', 'embed', 'hr', 'img', 'input', 'link', 'meta', 'param', 'source', 'track', 'wbr']);
				return {
					Parser: function(handlers, options) {
						this.handlers = handlers || {};
						this.options = options || {};
						this.tagStack = [];
						this.isVoidElement = function(tagName) { return voidElements.has(tagName.toLowerCase()); };

						this.write = function(html) {
							const tagRegex = /<(\/?)([\w-]+)([^>]*)>/g;
							let match;
							let lastIndex = 0;
							while ((match = tagRegex.exec(html)) !== null) {
								if (match.index > lastIndex) {
									const text = html.substring(lastIndex, match.index);
									if (text && this.handlers.ontext) this.handlers.ontext(text);
								}
								const isClosing = match[1] === '/';
								const tagName = match[2].toLowerCase();
								const attrsStr = match[3];
								const isSelfClosing = attrsStr.trim().endsWith('/');

								if (isClosing) {
									if (this.handlers.onclosetag) this.handlers.onclosetag(tagName);
								} else {
									const attrs = {};
									const attrRegex = /([\w-]+)(?:=["']([^"']*)["'])?/g;
									let attrMatch;
									while ((attrMatch = attrRegex.exec(attrsStr)) !== null) {
										if (attrMatch[1] && attrMatch[1] !== '/') attrs[attrMatch[1]] = attrMatch[2] || '';
									}
									if (this.handlers.onopentag) this.handlers.onopentag(tagName, attrs);
									if (voidElements.has(tagName) || isSelfClosing) {
										if (this.handlers.onclosetag) this.handlers.onclosetag(tagName);
									}
								}
								lastIndex = tagRegex.lastIndex;
							}
							if (lastIndex < html.length) {
								const text = html.substring(lastIndex);
								if (text && this.handlers.ontext) this.handlers.ontext(text);
							}
						};
						this.end = function() {
							if (this.handlers.onend) this.handlers.onend();
						};
					}
				};
			})()
		""".trimIndent()
	}

	private fun getNovelStatusLibrary(): String {
		return """
			(function() {
				return {
					NovelStatus: {
						Unknown: 0,
						Ongoing: 1,
						Completed: 2,
						Licensed: 3,
						PublishingFinished: 4,
						Cancelled: 5,
						OnHiatus: 6
					}
				};
			})()
		""".trimIndent()
	}

	private fun getFilterInputsLibrary(): String {
		return """
			(function() {
				return {
					FilterTypes: {
						Picker: 'Picker',
						Text: 'Text',
						TextInput: 'Text',
						Switch: 'Switch',
						Checkbox: 'Checkbox',
						CheckboxGroup: 'Checkbox',
						ExcludableCheckbox: 'ExcludableCheckbox',
						ExcludableCheckboxGroup: 'XCheckbox',
						TriState: 'TriState',
						Sort: 'Sort',
						Title: 'Title'
					}
				};
			})()
		""".trimIndent()
	}

}

/**
 * Exception thrown by LNReader JS engine operations.
 */
class LNReaderJSException(
	message: String,
	cause: Throwable? = null
) : Exception(message, cause)
