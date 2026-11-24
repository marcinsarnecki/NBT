package net.querz.mca;

/** How structure placement treats listed air states. Omitted positions are always left alone. */
public enum AirMode {
	/** Write each listed air state, even over a non-air block. */
	PLACE_EXPLICIT,
	/** Skip listed air, cave air, and void air. */
	SKIP_EXPLICIT
}
