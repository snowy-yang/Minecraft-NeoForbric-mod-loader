package neoforbric.subscribercanary;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;

@Mod("neoforbricsubscribercanary")
public final class NeoForbricSubscriberCanary {
	public NeoForbricSubscriberCanary(IEventBus modBus) {
		System.out.println("[NeoForbricSubscriberCanary] constructed");
	}
}
