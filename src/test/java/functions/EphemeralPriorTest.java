package functions;

import ec.EvolutionState;
import ec.util.MersenneTwisterFast;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sanity checks on the Ephemeral random-constant prior:
 *  - Draws span both negative and positive values (old prior was [0,1)).
 *  - Magnitudes reach beyond 1 thanks to the log-uniform branch.
 *  - At least some draws land on the integer branch.
 */
class EphemeralPriorTest {

    @Test
    void priorCoversNegativeValuesAndMagnitudesAbove10() throws Exception {
        EvolutionState state = new EvolutionState();
        state.random = new MersenneTwisterFast[] { new MersenneTwisterFast(123456L) };

        Ephemeral node = new Ephemeral();
        Field valueField = Ephemeral.class.getDeclaredField("value");
        valueField.setAccessible(true);

        int draws = 5000;
        int negative = 0;
        int aboveTen = 0;
        int integerLike = 0;
        for (int i = 0; i < draws; i++) {
            node.resetNode(state, 0);
            double v = valueField.getDouble(node);
            if (v < 0) negative++;
            if (Math.abs(v) > 10) aboveTen++;
            if (v == Math.rint(v) && Math.abs(v) <= 5) integerLike++;
        }
        assertTrue(negative > draws / 5, "negative draws should be a meaningful fraction, got " + negative);
        assertTrue(aboveTen > 10, "log-uniform branch should occasionally exceed 10, got " + aboveTen);
        assertTrue(integerLike > draws / 20, "integer branch should fire, got " + integerLike);
    }
}
