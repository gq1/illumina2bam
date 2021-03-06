/*
 * Copyright (C) 2011 GRL
 *
 * This library is free software. You can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

package uk.ac.sanger.npg.picard;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import net.sf.picard.cmdline.Option;
import net.sf.picard.cmdline.StandardOptionDefinitions;
import net.sf.picard.cmdline.Usage;
import net.sf.picard.io.IoUtil;
import net.sf.picard.util.Log;
import net.sf.samtools.*;
import uk.ac.sanger.npg.bam.util.BamUtils;


/**
 * The class to strip part of a read (fixed position) - typically a prefix of the forward read,
 * and optionally place this and its quality in BAM tags.
 * 
 * @author gq1@sanger.ac.uk
 */

public class BamReadTrimmer extends PicardCommandLine {
    
    private final Log log = Log.getInstance(BamReadTrimmer.class);
    
    private final String programName = "BamReadTrimmer";
    
    private final String programDS = "Strip part of a read in fixed positionos, optionally place this and its quality in BAM tags";
   
    @Usage(programVersion= version)
    public final String USAGE = this.getStandardUsagePreamble() + this.programDS + ". "; 
 
    @Option(shortName= StandardOptionDefinitions.INPUT_SHORT_NAME, doc="The input SAM or BAM file to trim.")
    public File INPUT;

    @Option(shortName=StandardOptionDefinitions.OUTPUT_SHORT_NAME, doc="The output file after trimming.")
    public File OUTPUT;

    @Option(shortName="FORWARD", doc="Just trim the forward read if true.")
    public Boolean ONLY_FORWARD_READ = true;
    
    @Option(shortName="POS", doc="First position to be trimmed.")
    public Integer FIRST_POSITION_TO_TRIM;
    
    @Option(shortName="LEN", doc="The lenght to be trimmed.")
    public Integer TRIM_LENGTH;
    
    @Option(shortName="SAVE", doc="Timmed bases to be saved?", optional=true)
    public Boolean SAVE_TRIM = true;
    
    @Option(shortName="RS", doc="Tag name to be used for timmed bases.", optional=true)
    public String TRIM_BASE_TAG = "rs";
    
    @Option(shortName="QS", doc="Tag name to be used for timmed qualities.", optional=true)
    public String TRIM_QUALITY_TAG = "qs";
    
    @Option(shortName="TAG", doc= "A list of tags to keep.")
    public final List<String> TAG_TO_KEEP = new ArrayList<String>();


    @Override
    protected int doWork() {
      
        this.log.info("Checking input and output file");
        IoUtil.assertFileIsReadable(INPUT);
        IoUtil.assertFileIsWritable(OUTPUT);
        
        log.info("Open input file: " + INPUT.getName());
        final SAMFileReader in  = new SAMFileReader(INPUT);
        
        final SAMFileHeader header = in.getFileHeader();
        final SAMFileHeader outputHeader = header.clone();
        outputHeader.setSequenceDictionary(new SAMSequenceDictionary());
        outputHeader.setSortOrder(SAMFileHeader.SortOrder.unsorted);
        this.addProgramRecordToHead(outputHeader, this.getThisProgramRecord(programName, programDS));
        
        log.info("Open output file with header: " + OUTPUT.getName());
        final SAMFileWriter out = new SAMFileWriterFactory().makeSAMOrBAMWriter(outputHeader,  true, OUTPUT);
        
        this.TAG_TO_KEEP.add("RG");
        
        log.info("Trimming records");
        for (SAMRecord record : in) {
  
            if ( this.ONLY_FORWARD_READ && record.getReadPairedFlag() && record.getSecondOfPairFlag() ){
                out.addAlignment( this.removeAlignment(record, outputHeader) );         
            }else{
                SAMRecord newRecord = this.removeAlignment(record, outputHeader);
                SAMRecord trimmedRecord = this.trimSAMRecord(newRecord, this.FIRST_POSITION_TO_TRIM, this.TRIM_LENGTH, this.SAVE_TRIM);
                out.addAlignment(trimmedRecord);
            }
        }
        
        out.close();
        log.info("Trimming finished, trimmied file: " + this.OUTPUT);
        
        return 0;
    }
    
    /**
     * 
     * @param record
     * @param header
     * @return
     */
    public SAMRecord removeAlignment(SAMRecord record, SAMFileHeader header){

        if (record.getReadNegativeStrandFlag()) {
             SAMRecordUtil.reverseComplement(record);
        }

        SAMRecord newRecord = new SAMRecord(header);
        
        newRecord.setReadName(record.getReadName());
        newRecord.setBaseQualities(record.getBaseQualities());
        newRecord.setReadBases(record.getReadBases());
        newRecord.setFirstOfPairFlag(record.getFirstOfPairFlag());
        newRecord.setReadPairedFlag(record.getReadPairedFlag());
        newRecord.setSecondOfPairFlag(record.getSecondOfPairFlag());
        newRecord.setReadUnmappedFlag(true);
        newRecord.setMateUnmappedFlag(true);
        
        for(String tag: this.TAG_TO_KEEP){
            newRecord.setAttribute(tag, record.getAttribute(tag));
        }
        
        return newRecord;
    }
    
    /**
     * 
     * @param record
     * @param firstPos
     * @param trimLength
     * @param saveTrim
     * @return
     */
    public SAMRecord trimSAMRecord(SAMRecord record, int firstPos, int trimLength, boolean saveTrim){

        boolean reversed = false;
        if (record.getReadNegativeStrandFlag()) {
             SAMRecordUtil.reverseComplement(record);
             reversed = true;
        }

        byte[] bases = record.getReadBases();
        byte[] qualities = record.getBaseQualities();

        final int readLength = bases.length;
        if(readLength != qualities.length){
            throw new RuntimeException("Read bases and qualities are not the same in lenght");
        }

        final int newReadLength = readLength - trimLength;
        
        byte[] basesTrimmed = new byte[trimLength];
        byte[] qualitiesTrimmed = new byte[trimLength];
        
        byte[] newBases = new byte[newReadLength];
        byte[] newQualities= new byte[newReadLength];
        
        
        int j = 0;
        int k = 0;
        
        for(int i = 0; i<readLength; i++){
            
            if((i+1) >= firstPos &&  k < trimLength) {                
                basesTrimmed[k] = bases[i];
                qualitiesTrimmed[k] = qualities[i];
                k++;
            }else{
                newBases[j] = bases[i];
                newQualities[j] = qualities[i];
                j++;
            }
        }
        record.setReadBases(newBases);
        record.setBaseQualities(newQualities);
        if(saveTrim){
            record.setAttribute(this.TRIM_BASE_TAG,   BamUtils.convertByteArrayToString( basesTrimmed));
            record.setAttribute(this.TRIM_QUALITY_TAG, BamUtils.convertPhredQualByteArrayToFastqString(qualitiesTrimmed));
        }

        if(reversed){
           SAMRecordUtil.reverseComplement(record);
        }
        
        return record;
    }
    
    
    
    /**
     * example:
     * INPUT=testdata/bam/6210_8.sam
     * OUTPUT=testdata/6210_8_trimmed.bam
     * FIRST_POSITION_TO_TRIM=1 TRIM_LENGTH=3
     * CREATE_MD5_FILE=true
     * ONLY_FORWARD_READ=true
     * SAVE_TRIM=true TRIM_BASE_TAG=rs TRIM_QUALITY_TAG=qs
     * VERBOSITY=INFO QUIET=false VALIDATION_STRINGENCY=SILENT
     *
     * INPUT=testdata/bam/6210_8.sam OUTPUT=testdata/6210_8_trimmed.bam FIRST_POSITION_TO_TRIM=1 TRIM_LENGTH=3 TMP_DIR=testdata CREATE_MD5_FILE=true ONLY_FORWARD_READ=true SAVE_TRIM=true TRIM_BASE_TAG=rs TRIM_QUALITY_TAG=qs VALIDATION_STRINGENCY=SILENT
     *
     * @param args 
     */
    public static void main(final String[] args) {
        
        System.exit(new BamReadTrimmer().instanceMain(args));
    }
}
