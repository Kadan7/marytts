package de.dfki.lt.mary.unitselection.concat;

import java.io.IOException;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

import javax.sound.sampled.AudioInputStream;

import de.dfki.lt.mary.unitselection.Datagram;
import de.dfki.lt.mary.unitselection.SelectedUnit;
import de.dfki.lt.mary.unitselection.Unit;
import de.dfki.lt.mary.unitselection.concat.BaseUnitConcatenator.UnitData;
import de.dfki.lt.signalproc.util.BufferedDoubleDataSource;
import de.dfki.lt.signalproc.util.DDSAudioInputStream;
import de.dfki.lt.signalproc.util.DoubleDataSource;

public class OverlapUnitConcatenator extends BaseUnitConcatenator {

    public OverlapUnitConcatenator()
    {
        super();
    }

    /**
     * Get the raw audio material for each unit from the timeline.
     * @param units
     */
    protected void getDatagramsFromTimeline(List units) throws IOException
    {
        for (int i=0, len=units.size(); i<len; i++) {
            SelectedUnit unit = (SelectedUnit) units.get(i);
            OverlapUnitData unitData = new OverlapUnitData();
            unit.setConcatenationData(unitData);
            int nSamples = 0;
            int unitSize = unitToTimeline(unit.getUnit().getDuration()); // convert to timeline samples
            long unitStart = unitToTimeline(unit.getUnit().getStart()); // convert to timeline samples
            //System.out.println("Unit size "+unitSize+", pitchmarksInUnit "+pitchmarksInUnit);
            Datagram[] datagrams = timeline.getDatagrams(unitStart,(long)unitSize);
            unitData.setFrames(datagrams);
            // one right context period for windowing:
            Datagram rightContextFrame = null;
            Unit nextInDB = database.getUnitFileReader().getNextUnit(unit.getUnit());
            Unit nextSelected;
            if (i+1==len) nextSelected = null;
            else nextSelected = ((SelectedUnit)units.get(i+1)).getUnit();
            if (nextInDB != null && !nextInDB.isEdgeUnit() && !nextInDB.equals(nextSelected)) {
                // Only use right context if we have a next unit in the DB, and it is not the
                // same as the next selected unit.
                rightContextFrame = timeline.getDatagram(unitStart+unitSize);
                unitData.setRightContextFrame(rightContextFrame);
            }
        }
    }
    
    /**
     * Determine target pitchmarks (= duration and f0) for each unit.
     * @param units
     */
    protected void determineTargetPitchmarks(List units)
    {
        for (Iterator it = units.iterator();it.hasNext();) {
            SelectedUnit unit = (SelectedUnit) it.next();
            UnitData unitData = (UnitData)unit.getConcatenationData();
            assert unitData != null : "Should not have null unitdata here";
            Datagram[] datagrams = unitData.getFrames();
            Datagram[] frames = null; // frames to realise
            // The number and duration of the frames to realise
            // must be the result of the target pitchmark computation.

            // Set target pitchmarks,
            // either by copying from units (data-driven)
            // or by computing from target (model-driven)
            int unitDuration = 0;
            int nZeroLengthDatagrams = 0;
            for (int i=0; i<datagrams.length; i++) {
                int dur = (int) datagrams[i].getDuration();
                if (dur == 0) nZeroLengthDatagrams++;
                unitDuration += datagrams[i].getDuration();
            }
            if (nZeroLengthDatagrams > 0) {
                logger.warn("Unit "+unit+" contains "+nZeroLengthDatagrams+" zero-length datagrams -- removing them");
                Datagram[] dummy = new Datagram[datagrams.length - nZeroLengthDatagrams];
                for (int i=0, j=0; i<datagrams.length; i++) {
                    if (datagrams[i].getDuration() > 0) {
                        dummy[j++] = datagrams[i];
                    }
                }
                datagrams = dummy;
                unitData.setFrames(datagrams);
            }
            if (unit.getTarget().isSilence()) {
                int targetDuration = Math.round(unit.getTarget().getTargetDurationInSeconds()*audioformat.getSampleRate());
                if (datagrams != null && datagrams.length > 0) {
                    int firstPeriodDur = (int) datagrams[0].getDuration();
                    if (targetDuration < firstPeriodDur) {
                        logger.debug("For "+unit+", adjusting target duration to be at least one period: "
                                + (firstPeriodDur/audioformat.getSampleRate())+" s instead of requested "+unit.getTarget().getTargetDurationInSeconds()+ " s");
                        targetDuration = firstPeriodDur;
                    }
                    if (unitDuration < targetDuration) {
                        // insert silence in the middle
                        frames = new Datagram[datagrams.length+1];
                        int mid = (datagrams.length+1) / 2;
                        System.arraycopy(datagrams, 0, frames, 0, mid);
                        if (mid < datagrams.length) {
                            System.arraycopy(datagrams, mid, frames, mid+1, datagrams.length-mid);
                        }
                        frames[mid] = createZeroDatagram(targetDuration - unitDuration);
                    } else { // unitDuration >= targetDuration
                        // cut frames from the middle
                        int midright = (datagrams.length+1) / 2; // first frame of the right part
                        int midleft = midright - 1; // last frame of the left part
                        while (unitDuration > targetDuration && midright < datagrams.length) {
                            unitDuration -= datagrams[midright].getDuration();
                            midright++;
                            if (unitDuration > targetDuration && midleft > 0) { // force it to leave at least one frame, therefore > 0
                                unitDuration -= datagrams[midleft].getDuration();
                                midleft--;
                            }
                        }
                        frames = new Datagram[midleft+1 + datagrams.length-midright];
                        assert midleft >= 0;
                        System.arraycopy(datagrams, 0, frames, 0, midleft+1);
                        if (midright < datagrams.length) {
                            System.arraycopy(datagrams, midright, frames, midleft+1, datagrams.length-midright);
                        }
                    }
                } else { // unitSize == 0, we have a zero-length silence unit
                    // artificial silence data:
                    frames = new Datagram[] { createZeroDatagram(targetDuration) };
                }
            } else { // not silence
                // take unit as is
                frames = datagrams;
            }
            unitData.setUnitDuration(unitDuration);
            unitData.setFrames(frames);
        }
    }
    
    /**
     * Generate audio to match the target pitchmarks as closely as possible.
     * @param units
     * @return
     */
    protected AudioInputStream generateAudioStream(List units)
    {
        int len = units.size();
        Datagram[][] datagrams = new Datagram[len][];
        Datagram[] rightContexts = new Datagram[len];
        for (int i=0; i<len; i++) {
            SelectedUnit unit = (SelectedUnit) units.get(i);
            OverlapUnitData unitData = (OverlapUnitData)unit.getConcatenationData();
            assert unitData != null : "Should not have null unitdata here";
            Datagram[] frames = unitData.getFrames();
            assert frames != null : "Cannot generate audio from null frames";
            // Generate audio from frames
            datagrams[i] = frames;
            rightContexts[i] = unitData.getRightContextFrame(); // may be null
        }
        
        DoubleDataSource audioSource = new DatagramOverlapDoubleDataSource(datagrams, rightContexts);
        return new DDSAudioInputStream(new BufferedDoubleDataSource(audioSource), audioformat);
    }

    protected static class OverlapUnitData extends BaseUnitConcatenator.UnitData
    {
        protected Datagram rightContextFrame;
        
        public void setRightContextFrame(Datagram aRightContextFrame)
        {
            this.rightContextFrame = aRightContextFrame;
        }
        
        public Datagram getRightContextFrame()
        {
            return rightContextFrame;
        }
    }
}
