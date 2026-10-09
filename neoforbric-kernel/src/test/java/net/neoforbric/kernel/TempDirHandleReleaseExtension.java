/*
 * Copyright 2026 The NeoForbric Project
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.neoforbric.kernel;

import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.InvocationInterceptor;
import org.junit.jupiter.api.extension.ReflectiveInvocationContext;

/**
 * Releases the loaders a test leaves behind before {@code @TempDir} is deleted.
 *
 * <p>The kernel's tests build jars in a temporary directory and load them through a {@code URLClassLoader}.
 * On Windows an open jar is an open file handle, and the runtime only lets go of it when the loader is collected.
 * Both usually happen after the test method returns but <em>before</em> JUnit reaches for the directory, so
 * {@code TempDirectory} reports "Failed to delete temp directory" and JUnit counts the test as failed even
 * though every assertion in it passed.
 *
 * <p>Collecting is enough: a {@code URLClassLoader}'s jar handles ride on a {@code java.lang.ref.Cleaner}
 * registered by {@code JarFile}, which the collector itself runs — no finalizer queue, no waiting on
 * {@code System.runFinalization()}.
 *
 * <p>This wraps the invocation rather than being an {@code AfterEachCallback} on purpose. A temp directory is
 * deleted by the extension JUnit registers when it resolves the parameter, which runs after the class-level
 * ones and therefore runs <em>first</em> on teardown: an after-each hook would be too late every time.
 */
public final class TempDirHandleReleaseExtension implements InvocationInterceptor {

	@Override
	public void interceptTestMethod(Invocation<Void> invocation, ReflectiveInvocationContext<java.lang.reflect.Method> invocationContext,
			ExtensionContext extensionContext) throws Throwable {
		try {
			invocation.proceed();
		} finally {
			// Whatever the test asserted, and whether it failed: a handle left behind by a loader the test no
			// longer names must not turn a red test green or a green one red.
			System.gc();
		}
	}
}
