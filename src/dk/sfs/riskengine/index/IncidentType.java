package dk.sfs.riskengine.index;

import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.Map;

import org.apache.ibatis.session.SqlSession;

import dk.sfs.riskengine.ais.RiskTarget;
import dk.sfs.riskengine.consequence.Consequence;
import dk.sfs.riskengine.consequence.Ship;
import dk.sfs.riskengine.geometry.CPA;
import dk.sfs.riskengine.geometry.Geofunctions;
import dk.sfs.riskengine.geometry.Point2d;
import dk.sfs.riskengine.metoc.Metoc;
import dk.sfs.riskengine.persistence.domain.DepthPoint;
import dk.sfs.riskengine.persistence.domain.Vessel.ShipTypeIwrap;
import dk.sfs.riskengine.persistence.mapper.AccidentFrequenceMapper;
import dk.sfs.riskengine.persistence.mapper.DBSessionFactory;
import dk.sfs.riskengine.persistence.mapper.RiskMapper;
import dk.sfs.riskengine.statistics.Normal;
import dk.sfs.riskengine.statistics.Weibull;

public abstract class IncidentType {

	protected Metoc metoc;
	protected RiskTarget vessel;
	protected RiskTarget otherVessel;

	private double pollutionCost;	//million US dollars
	private double materialCost;	//million US dollars
	private double humanCost;		//million US dollars
	
	private double maxConsequence;	//The cost if the ship sink, everybody dies and all the oil pollutes the coast line
	private double maxProbability;	//The probability of the incident when all parameters are bad, (wind, age, etc.)
	
	private double consequence;	//The absolute consequence in mill. $
	private double probability;	//The absolute probability of the incident
	private double riskIndex;	//The absolute risk index in mill. $
	
	private double probabilityNormalized;			//The normalized consequence [0-1]	1=the maximum possible consequence
	protected double consequenceNormalized;	//The normalized probability of the incident [0-1]	1=highest probability
	private double riskIndexNormalized;	//The normalized risk index [0-1]
	/*
	 * Default values
	 */
	private static final boolean DEFAULT_SOFT_BOTTOM = true; // in Denmark this
															 // is usually true.
	private static final double DEFAULT_TIME_TO_RESCUE = 1.0; // Hours

	public enum AccidentType {
		COLLISION, FOUNDERING, HULLFAILURE, MACHINERYFAILURE, FIRE_EXPLOSION, POWEREDGROUNDING, DRIFTGROUNDING
	}

	public enum ShipSize {
		SMALL, MEDIUM, LARGE;
	}

	/**
	 * Instantiate and calculate risk index and consequence index for incident
	 * involving own ship only
	 * 
	 * @param metoc
	 * @param target
	 */
	public IncidentType(Metoc metoc, RiskTarget target) {
		this(metoc, target, null);
		
	}

	/**
	 * Instantiate and calculate risk index and consequence index for incident
	 * involving own ship and another ship, i.e collision
	 * 
	 * @param metoc
	 * @param target
	 * @param other
	 */
	public IncidentType(Metoc metoc, RiskTarget target, RiskTarget other) {
		super();
		this.vessel = target;
		this.metoc = metoc;
		this.otherVessel = other;
		setProbability();
		setConsequence();
		
		riskIndex=probability*consequence;
		riskIndexNormalized=probabilityNormalized*consequenceNormalized;
	}

	/**
	 * set a normalised value for the consequence index
	 */
	private void setConsequence() {
		Ship ship1=vessel.getConsequenceShip();
		Ship otherShip = null;
		if (otherVessel != null) {
			otherShip = otherVessel.getConsequenceShip();
			otherShip.EstimateShipParameters(false, false);
		}
		
		// Ship1 is the damaged ship. 
		ship1.EstimateShipParameters(false, false); // If possible get them using the
											// ships IMO and lloyds table.
		Consequence consequenceObj=new Consequence();
		
		consequence = consequenceObj.getConsequence(getAccidentType(), ship1,
				metoc.getWaweHeight(), DEFAULT_SOFT_BOTTOM, DEFAULT_TIME_TO_RESCUE, metoc.getAirTemp(), otherShip);
		
		pollutionCost=consequenceObj.getPollutionCost();
		humanCost=consequenceObj.getHumanCost();
		materialCost=consequenceObj.getMaterialCost();
		
		/*
		 * Normalise
		 * Would like to calculate a normal consequence. But because the consequence calculation contains 
		 * many random values this is not good. Instead we calculate the maximum possible cost.
		 */
		//double normal = Consequence.getConsequence(getAccidentType(), vessel.getConsequenceShip(),
		//		Metoc.DEFAULT_WAWE_HEIGHT, DEFAULT_SOFT_BOTTOM, DEFAULT_TIME_TO_RESCUE, Metoc.DEFAULT_AIR_TEMP,
		//		otherShip);
		
		maxConsequence=consequenceObj.getMaxConsequence(ship1);
		if (maxConsequence == 0.0)
			consequenceNormalized = 0.0;
		else
			consequenceNormalized = consequence/maxConsequence;
			
		if (consequenceNormalized>1.0) consequenceNormalized=1.0;	//Should not be nesersary, but just in case.
	}

	/**
	 * set a normalised value for the risk probability
	 */
	private void setProbability() {
		// requires static info
		double c = getCasualtyRate(vessel.getShipTypeIwrap(), vessel.getLength());
		c*=((System.currentTimeMillis() - vessel.getLastUpdated() )/ (365.25 * 24d * 60d*60d*1000d));
		maxProbability = c*getMaxAgeFactor()*getMaxFlagFactor()*getMaxWindcurrentFactor()*getMaxVisibilityFactor() * getMaxExposure();

		if (vessel.getYearOfBuild() != null) {
			c *= getAgeFactor(new GregorianCalendar().get(Calendar.YEAR) - vessel.getYearOfBuild());
		}
		if (vessel.getFlag() != null) {
			c *= getFlagFactor(vessel.getFlag());
		}
		
		probability = c * getWindcurrentFactor() * getVisibilityFactor() * getExposure();
		if (maxProbability == 0.0)
			probabilityNormalized=0.0;
		else
			probabilityNormalized=probability/maxProbability;
		
		if (probabilityNormalized>1.0) probabilityNormalized=1.0;	//Should not be nesesary, but just in case.
	}

	
	protected abstract double getAgeFactorParam();

	protected double getAgeFactor(double age) {
		double age0=age;
		if (age0>25.0) age0=25.0;
		return Math.exp(getAgeFactorParam() * age0);
	}
	
	protected double getMaxAgeFactor() {
		return getAgeFactor(25.0);
	}

	protected double getFlagFactor(String flag) {
		return 1.0;
	}
	
	protected double getMaxFlagFactor() {
		return 1.0;
	}

	/**
	 * Default wind factor. Override for incident specific wind factor
	 * 
	 * @return
	 */
	public double getWindcurrentFactor() {
		double f=1.0;
		double maxWindSpeeed=45.0;
		double windspeed=metoc.getWindSpeed();
		if (windspeed>maxWindSpeeed) windspeed=maxWindSpeeed;
		f=Math.exp(0.03 * windspeed);
		return f;
	}

	public double getMaxWindcurrentFactor() {
		double maxWindSpeeed=45.0;
		return Math.exp(0.03 * maxWindSpeeed);
	}
	
	/**
	 * Override for incident specific visibility factor when visibility is
	 * available.
	 * 
	 * @param visibility
	 * 
	 * @return
	 */
	protected final double getVisibilityFactor() {
		return 1.0;
	}
	
	protected final double getMaxVisibilityFactor() {
		return 1.0;
	}

	/**
	 * Factor based on navigational status. Nav status is not reliable !!
	 * 
	 * @param navStat
	 * @return
	 */
	protected double getNavStatFactor(int navStat) {
		return 1.0;
	}

	
	protected double getExposure() {
		return 1.0;
	}
	
	protected double getMaxExposure() {
		return 1.0;
	}
	
	protected Double getCasualtyRate(ShipTypeIwrap shipTypeIwrap, double shipsize) {

		SqlSession sess = DBSessionFactory.getSession();

		try {
			AccidentFrequenceMapper mapper = sess.getMapper(AccidentFrequenceMapper.class);
			Map<String, Object> map = new HashMap<String, Object>();
			map.put("accidentTypeName", getAccidentType().name());
			map.put("shipTypeName", shipTypeIwrap.getIwrapName());
			ShipSize size;

			if (shipsize > 200) {
				size = ShipSize.LARGE;
			} else if (shipsize < 70) {
				size = ShipSize.SMALL;
			} else {
				size = ShipSize.MEDIUM;
			}

			map.put("shipSize", size.name());
			return mapper.selectByShipTypeAndAccidentType(map);
		} finally {
			sess.close();
		}

	}

	protected static Double selectAvgByAccidentType(String accidentName) {
		SqlSession sess = DBSessionFactory.getSession();

		try {
			AccidentFrequenceMapper mapper = sess.getMapper(AccidentFrequenceMapper.class);
			return mapper.selectAvgByAccidentType(accidentName);
		} finally {
			sess.close();
		}
	}

	/**
	 * @return a vector where x is the speed in knots and y is the compass
	 *         direction
	 */
	protected Point2d estimateCombinedWindCurrentDrift() {

		/*
		 * Build a wind vector
		 */
		// Translate wind speed into a speed vector. Wind direction is opposite.
		double angle = Geofunctions.compass2cartesian(metoc.getWindDirection()) + 180.0;

		if (angle >= 360.0) {
			angle = 360.0 - angle;
		}
		Point2d w = Point2d.getUnitVector(angle);

		// assume that the drifting ship will move with 15% of the wind speed.
		// TODO make it a function of the ships superstructure
		w = w.Multiply(metoc.getWindSpeed() * 0.15);

		/*
		 * Builds a current vector
		 */
		angle = Geofunctions.compass2cartesian(metoc.getCurrentDirection());
		Point2d c = Point2d.getUnitVector(angle);
		c = c.Multiply(metoc.getCurrentSpeed() * 0.514444);

		// Add vectors
		Point2d p = w.Plus(c);

		Point2d rst = new Point2d();
		rst.x = p.length() / 0.514444; // Speed in knots
		rst.y = p.getAngle(); // Direction
		rst.y = Geofunctions.cartesian2compass(rst.y);

		return rst;
	}

	/**
	 * Caluclate the time before the ship will strand if continuing in same
	 * direction, same speed.
	 * 
	 * @param speed
	 *            of ship in knots
	 * @param direction
	 *            of ship in degree (0=North, clockwise)
	 * 
	 * @return time in seconds
	 */

	protected double getTimeToGrounding(double speed, double direction) {

		
		DepthPoint ground = DepthPoint.findGroundingPoint(vessel.getPos(), direction, vessel.getDraught());
		if (ground == null) {
			/*
			 * Ingen ground forward
			 */
			return Double.POSITIVE_INFINITY;
		}
		/*
		 * get distance from ship to grounding point
		 */
		Point2d pos = new Point2d();
		pos.setLatLon(vessel.getGeoLocation().getLongitude(), vessel.getGeoLocation().getLatitude());
		Double dist = pos.distanceLatLon(ground.getLon(), ground.getLat());
		/*
		 * return time to grounding
		 */
		return dist / CPA.KnotsToMs(speed);
	}
	
	public abstract AccidentType getAccidentType();
	
	
	//Must be estimated before they can be fetched
	public double getHumanCost() {
		return humanCost;
	}
	
	public double getPollutionCost() {
		return pollutionCost;
	}
	
	public double getMaterialCost() {
		return materialCost;
	}
	
	public double getMaxProbability() {
		return maxProbability;
	}
	
	public double getMaxConsequence() {
		return maxConsequence;
	}
	
	public double getProbability() {
		return probability;
	}
	
	public double getProbabilityNormalized() {
		return probabilityNormalized;
	}

	public double getConsequenceNormalized() {
		return consequenceNormalized;
	}
	
	public double getConsequence() {
		return consequence;
	}
	
	//The following 6 set methods should only be used in special cases
	public void setProbability(double x) {
		probability=x;
	}
	
	public void setProbabilityNormalized(double x) {
		probabilityNormalized=x;
	}

	public void setConsequenceNormalized(double x) {
		consequenceNormalized=x;
	}
	
	public void setConsequence(double x) {
		consequence=x;
	}
	
	public void setRiskIndexNormalized(double x) {
		riskIndexNormalized=x;
	}
	
	public void setRiskIndex(double x) {
		riskIndex=x;
	}

	
	public double getRiskIndexNormalized() {
		riskIndexNormalized=probabilityNormalized*consequenceNormalized;	//Should be somewhere else. Make sure they have been calculated
		return riskIndexNormalized;
	}
	
	public double getRiskIndex() {
		riskIndex=probability*consequence;	//Should be somewhere else. Make sure they have been calculated
		return riskIndex;
	}

	public Long getMmsi() {
		return vessel.getMmsi();
	}

	public void save() {
		SqlSession sess = DBSessionFactory.getSession();
		try {
			sess.getMapper(RiskMapper.class).insert(this);
		} finally {
			sess.close();
		}
	}

	@SuppressWarnings("unused")
	private RiskTarget getVessel() {
		return vessel;
	}

}
