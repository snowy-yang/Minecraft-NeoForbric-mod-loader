/*
 * Copyright 2026 The NeoForbric Project
 *
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

package net.neoforbric.loader.impl.forge.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class NeoForbricKnownPackIdentityTest {
	@Test
	void buildsStableKnownPackIdentity() {
		NeoForbricKnownPackIdentity.Descriptor client =
				NeoForbricKnownPackIdentity.descriptor("forge", NeoForbricKnownPackIdentity.CLIENT_RESOURCES,
						"physicsmod", "3.1.45");
		NeoForbricKnownPackIdentity.Descriptor server =
				NeoForbricKnownPackIdentity.descriptor("forge", NeoForbricKnownPackIdentity.SERVER_DATA,
						"physicsmod", "3.1.45");

		assertEquals("neoforbric/forge/client_resources/physicsmod", client.locationId());
		assertEquals("forge/client_resources/physicsmod", client.knownPackId());
		assertEquals("neoforbric", client.knownPackNamespace());
		assertEquals("3.1.45", client.version());

		assertEquals("neoforbric/forge/server_data/physicsmod", server.locationId());
		assertEquals("forge/server_data/physicsmod", server.knownPackId());
		assertEquals(client.knownPackNamespace(), server.knownPackNamespace());
		assertEquals(client.version(), server.version());
	}

	@Test
	void normalizesBlankVersion() {
		NeoForbricKnownPackIdentity.Descriptor descriptor =
				NeoForbricKnownPackIdentity.descriptor("forge", NeoForbricKnownPackIdentity.CLIENT_RESOURCES,
						"example", "   ");
		assertEquals("0", descriptor.version());
	}
}
