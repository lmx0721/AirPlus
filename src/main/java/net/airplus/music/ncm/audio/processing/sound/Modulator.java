// skid SetsunaClient
package net.airplus.music.ncm.audio.processing.sound;

import net.airplus.music.ncm.audio.com.jsyn.ports.UnitOutputPort;

/**
 * Interface for any object that can be passed as a modulator to an oscillator's 
 * freq() and amp() methods.
 */
public interface Modulator {

	UnitOutputPort getModulator();

}
