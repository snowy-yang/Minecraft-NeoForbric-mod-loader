package neoforbric.outcome.mixin;
import neoforbric.outcome.OutcomeTarget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
@Mixin(OutcomeTarget.class)
public abstract class WidenedArgumentMixin {
 @ModifyArg(method="run",at=@At(value="INVOKE",target="Lneoforbric/outcome/ValueCarrier;apply(Ljava/lang/String;)Ljava/lang/String;"),index=0)
 private String change(String value){return "changed";}
}
