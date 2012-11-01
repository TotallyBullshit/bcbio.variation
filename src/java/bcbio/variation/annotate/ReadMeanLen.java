package bcbio.variation.annotate;

import net.sf.samtools.Cigar;
import net.sf.samtools.CigarElement;
import net.sf.samtools.CigarOperator;
import net.sf.samtools.SAMRecord;

import org.broadinstitute.sting.gatk.walkers.genotyper.IndelGenotypeLikelihoodsCalculationModel;
import org.broadinstitute.sting.gatk.walkers.indels.PairHMMIndelErrorModel;
import org.broadinstitute.sting.gatk.contexts.AlignmentContext;
import org.broadinstitute.sting.gatk.contexts.ReferenceContext;
import org.broadinstitute.sting.gatk.refdata.RefMetaDataTracker;
import org.broadinstitute.sting.gatk.walkers.annotator.interfaces.ActiveRegionBasedAnnotation;
import org.broadinstitute.sting.gatk.walkers.annotator.interfaces.AnnotatorCompatible;
import org.broadinstitute.sting.gatk.walkers.annotator.interfaces.InfoFieldAnnotation;
import org.broadinstitute.sting.gatk.walkers.annotator.interfaces.ExperimentalAnnotation;
import org.broadinstitute.sting.utils.genotyper.PerReadAlleleLikelihoodMap;
import org.broadinstitute.sting.utils.MathUtils;
import org.broadinstitute.sting.utils.QualityUtils;
import org.broadinstitute.sting.utils.codecs.vcf.VCFConstants;
import org.broadinstitute.sting.utils.codecs.vcf.VCFHeaderLineType;
import org.broadinstitute.sting.utils.codecs.vcf.VCFInfoHeaderLine;
import org.broadinstitute.sting.utils.pileup.PileupElement;
import org.broadinstitute.sting.utils.pileup.ReadBackedPileup;
import org.broadinstitute.sting.utils.sam.AlignmentUtils;
import org.broadinstitute.sting.utils.sam.GATKSAMRecord;
import org.broadinstitute.sting.utils.sam.ReadUtils;
import org.broadinstitute.sting.utils.variantcontext.Allele;
import org.broadinstitute.sting.utils.variantcontext.VariantContext;

import java.util.*;

/**
 * Calculates parameters related to artifacts of incorrect local alignments
 * Mean number of aligned bases for reads covering this position - lower numbers indicative of local alignment difficulty
 * written by Justin Zook - 7/5/12
 */
public class ReadMeanLen extends InfoFieldAnnotation implements ExperimentalAnnotation {

    public List<String> getKeyNames() {
        return Arrays.asList("ReadMeanLen");
    }

    public List<VCFInfoHeaderLine> getDescriptions() {
        return Arrays.asList(new VCFInfoHeaderLine("ReadMeanLen", 1, VCFHeaderLineType.Float, "Mean number of aligned bases for reads - low number indicate possible mis-alignments"));
    }

  public Map<String, Object> annotate(RefMetaDataTracker tracker, AnnotatorCompatible walker, ReferenceContext ref, Map<String, AlignmentContext> stratifiedContexts, VariantContext vc, final Map<String, PerReadAlleleLikelihoodMap> stratifiedLikelihoodMap) {
        if ( stratifiedContexts.size() == 0 )
            return null;

        double readLenSum = 0;
		double numReads = 0;
        for ( Map.Entry<String, AlignmentContext> sample : stratifiedContexts.entrySet() ) {
            for ( PileupElement p : sample.getValue().getBasePileup() )	{
        	        int readPos = AlignmentUtils.calcAlignmentByteArrayOffset(p.getRead().getCigar(), p, 0, 0);
        	        final int numAlignedBases = AlignmentUtils.getNumAlignedBases(p.getRead());

			readLenSum+=((double) numAlignedBases);

			numReads+=1;
            }
        }
        Map<String, Object> map = new HashMap<String, Object>();
        map.put(getKeyNames().get(0), String.format("%.01f", readLenSum/numReads));
        return map;
    }



    int getNumClippedBasesAtStart(SAMRecord read) {
        // compute total number of clipped bases (soft or hard clipped)
        // check for hard clips (never consider these bases):
        final Cigar c = read.getCigar();
        final CigarElement first = c.getCigarElement(0);

        int numStartClippedBases = 0;
        if (first.getOperator() == CigarOperator.H) {
            numStartClippedBases = first.getLength();
        }
        byte[] unclippedReadBases = read.getReadBases();
        byte[] unclippedReadQuals = read.getBaseQualities();

        // Do a stricter base clipping than provided by CIGAR string, since this one may be too conservative,
        // and may leave a string of Q2 bases still hanging off the reads.
        for (int i = numStartClippedBases; i < unclippedReadBases.length; i++) {
            if (unclippedReadQuals[i] < PairHMMIndelErrorModel.BASE_QUAL_THRESHOLD)
                numStartClippedBases++;
            else
                break;

        }

        return numStartClippedBases;
    }

    int getNumAlignedBases(SAMRecord read) {
        return read.getReadLength() - getNumClippedBasesAtStart(read) - getNumClippedBasesAtEnd(read);
    }

    int getNumClippedBasesAtEnd(SAMRecord read) {
        // compute total number of clipped bases (soft or hard clipped)
        // check for hard clips (never consider these bases):
        final Cigar c = read.getCigar();
        CigarElement last = c.getCigarElement(c.numCigarElements() - 1);

        int numEndClippedBases = 0;
        if (last.getOperator() == CigarOperator.H) {
            numEndClippedBases = last.getLength();
        }
        byte[] unclippedReadBases = read.getReadBases();
        byte[] unclippedReadQuals = read.getBaseQualities();

        // Do a stricter base clipping than provided by CIGAR string, since this one may be too conservative,
        // and may leave a string of Q2 bases still hanging off the reads.
        for (int i = unclippedReadBases.length - numEndClippedBases - 1; i >= 0; i--) {
            if (unclippedReadQuals[i] < PairHMMIndelErrorModel.BASE_QUAL_THRESHOLD)
                numEndClippedBases++;
            else
                break;
        }


        return numEndClippedBases;
    }

    int getOffsetFromClippedReadStart(SAMRecord read, int offset) {
        return offset - getNumClippedBasesAtStart(read);
    }
}



