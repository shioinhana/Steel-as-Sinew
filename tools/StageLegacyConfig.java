import java.nio.file.Path;
import org.fsrpg.config.RpgConfig;

/** Uses the exact release's parser and field-preserving migration before deployment. */
public final class StageLegacyConfig {
    public static void main(String[] args) throws Exception {
        if(args.length!=1)throw new IllegalArgumentException("Expected the staged config directory");
        var config=new RpgConfig(Path.of(args[0]));var definitions=config.reload();
        System.out.println("Migrated and validated: "+definitions.skills().size()+" skills, "+definitions.styles().size()+" styles, "+definitions.skillTrees().nodes().size()+" tree nodes");
    }
}
