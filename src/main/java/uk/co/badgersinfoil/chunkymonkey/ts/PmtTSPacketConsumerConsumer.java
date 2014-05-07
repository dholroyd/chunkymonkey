package uk.co.badgersinfoil.chunkymonkey.ts;

import uk.co.badgersinfoil.chunkymonkey.ts.ProgramAssociationTable.ProgramEntry;

public class PmtTSPacketConsumerConsumer implements TSPacketConsumer {

	public class PMTContext implements TSContext {

	}

	private PmtConsumer pmtConsumer;

	public PmtTSPacketConsumerConsumer(PmtConsumer pmtConsumer)
	{
		this.pmtConsumer = pmtConsumer;
	}

	@Override
	public void packet(TSContext ctx, TSPacket packet) {
		ProgramTSContext progCtx = (ProgramTSContext)ctx;
		ProgramMapTable pmt = progCtx.lastPmt();
		if (packet.payloadUnitStartIndicator()) {
			if (pmt != null && !pmt.isComplete()) {
				System.err.println("Last PMT incomplete at start of new PMT");
			}
			pmt = new ProgramMapTable(packet.getLocator(), packet.getPayload());
			progCtx.lastPmt(pmt);
		} else {
			if (pmt == null) {
				// ignore PMT continuation when payload start missed
			} else {
				if (pmt.isComplete()) {
					System.err.println("Last PMT complete, but more payload arrived");
					return;
				}
				pmt.appendPayload(packet.getPayload());
			}
		}
		if (pmt != null && pmt.isComplete()) {
			pmtConsumer.handle(progCtx, pmt);
		}
	}

	public ProgramTSContext createContext(TransportContext ctx, ProgramEntry entry) {
		return new ProgramTSContext(ctx);
	}

	@Override
	public void end(TSContext ctx) {
		ProgramTSContext progCtx = (ProgramTSContext)ctx;
	}

	@Override
	public TSContext createContext(TSContext parent) {
		return new PMTContext();
	}
}
