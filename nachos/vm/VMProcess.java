package nachos.vm;

import nachos.machine.*;
import nachos.threads.*;
import nachos.userprog.*;
import nachos.vm.*;

import javax.crypto.Mac;
import java.util.Random;

/**
 * A <tt>UserProcess</tt> that supports demand-paging.
 */
public class VMProcess extends UserProcess {
    /**
     * Allocate a new process.
     */
    public VMProcess() {
        super();
    }

    /**
     * Save the state of this process in preparation for a context switch.
     * Called by <tt>UThread.saveState()</tt>.
     */
    public void saveState() {
        super.saveState();
    }

    /**
     * Restore the state of this process after a context switch. Called by
     * <tt>UThread.restoreState()</tt>.
     */
    public void restoreState() {

        for (int i = 0; i < Machine.processor().getTLBSize(); i++) {
           TranslationEntry entry = Machine.processor().readTLBEntry(i);
           entry.valid = false;
           Machine.processor().writeTLBEntry(i, entry);
        }
        //super.restoreState();
    }

    /**
     * Initializes page tables for this process so that the executable can be
     * demand-paged.
     *
     * @return	<tt>true</tt> if successful.
     */
    protected boolean loadSections() {
        //return super.loadSections();
        return true;
    }

    protected TranslationEntry loadPageIntoMemory (int vpn, int ppn) {
        // swap space checking
        for (int s = 0; s < coff.getNumSections(); s++) {
            CoffSection section = coff.getSection(s);

            System.out.println("section " + s + " sectionfirstvpn " +
                section.getFirstVPN() + " sectionlength " + section.getLength() +
                    " vpn " + vpn);
            if (vpn < section.getFirstVPN() + section.getLength()) {
                int pos = vpn - section.getFirstVPN();

                section.loadPage(pos, ppn);
                TranslationEntry entry = new TranslationEntry(
                    vpn, ppn, true, section.isReadOnly(), false, false
                );
                VMKernel.invertedPageTable.put(new Pair(vpn, processID),
                        entry);

                return entry;
            }
        }
        TranslationEntry entry = new TranslationEntry(
                vpn, ppn, true, false, false, false
        );
        VMKernel.invertedPageTable.put(new Pair(vpn, processID),
                entry);

        return entry;
    }

    /**
     * Release any resources allocated by <tt>loadSections()</tt>.
     */
    protected void unloadSections() {
        super.unloadSections();
    }

    protected void handleTLBMiss (int vaddr) {

        int vpn = Processor.pageFromAddress(vaddr);
        bringPage(vpn);
    }

    protected int findTLBIndToEvict() {

        for (int i = 0; i < Machine.processor().getTLBSize(); i++) {
            TranslationEntry tlbEntry = Machine.processor().readTLBEntry(i);
            Lib.assertTrue(tlbEntry != null);

            if (!tlbEntry.valid){
                return i;
            }
        }
        return new Random().nextInt(Machine.processor().getTLBSize());
    }

    protected void writeTLBBack (int tlbId) {
        TranslationEntry entry = Machine.processor().readTLBEntry(tlbId);

        if (entry.valid && entry.dirty) {
            TranslationEntry pageTableEntry = VMKernel.
                    invertedPageTable.get( new Pair(entry.vpn, processID));
            Lib.assertTrue(pageTableEntry != null,
                    "TLB has an entry, pageTable doesn't");

            pageTableEntry.dirty = true;
            pageTableEntry.used = entry.used;
        }
    }

    protected int bringPage (int vpn) {
        Pair key = new Pair(vpn, processID);

        int evictedTLBId = findTLBIndToEvict();
        writeTLBBack(evictedTLBId);

        TranslationEntry alreadyFoundEntry = VMKernel.
                invertedPageTable.get(key);

        if (alreadyFoundEntry != null){ // Page Table Hit
            Machine.processor().writeTLBEntry(evictedTLBId, alreadyFoundEntry);
            return alreadyFoundEntry.ppn;
        }
        else {     // Page Table miss
            Integer evictedPPN = VMKernel.invertedPageTable.
                    evictPhysicalPageNumber();

            Lib.assertTrue(evictedPPN != -1);

            TranslationEntry loadedEntry = loadPageIntoMemory(vpn, evictedPPN);

            Machine.processor().writeTLBEntry(evictedTLBId, loadedEntry);

            return loadedEntry.ppn;
        }
    }

    public int translateVirtualPage (int vpn){

        for (int i = 0; i < Machine.processor().getTLBSize(); i++) {
            TranslationEntry tlbEntry = Machine.processor().readTLBEntry(i);
            Lib.assertTrue(tlbEntry != null);

            if (tlbEntry.valid && tlbEntry.vpn == vpn) {
                return tlbEntry.ppn;
            }
        }

        return bringPage(vpn);
    }

    public boolean checkValidVPN (int vpn) {
        return vpn >= 0 && vpn < numPages;
    }

    public void updateTLBEntry (int vpn, boolean isDirty) {

        for (int i = 0; i < Machine.processor().getTLBSize(); i++) {
            TranslationEntry tlbEntry = Machine.processor().readTLBEntry(i);
            Lib.assertTrue(tlbEntry != null);

            if (tlbEntry.valid && tlbEntry.vpn == vpn) {
                tlbEntry.dirty = isDirty;
                tlbEntry.used = true;

                Machine.processor().writeTLBEntry(i, tlbEntry);
                return;
            }
        }
        Lib.assertNotReached();
    }

    /**
     * Handle a user exception. Called by
     * <tt>UserKernel.exceptionHandler()</tt>. The
     * <i>cause</i> argument identifies which exception occurred; see the
     * <tt>Processor.exceptionZZZ</tt> constants.
     *
     * @param	cause	the user exception that occurred.
     */
    public void handleException(int cause) {
        boolean intStatus = Machine.interrupt().disable();
        Processor processor = Machine.processor();

        switch (cause) {

            case Processor.exceptionTLBMiss:
                handleTLBMiss(processor.readRegister(Processor.regBadVAddr));
                break;
            default:
                super.handleException(cause);
                break;
        }

        Machine.interrupt().restore(intStatus);
    }

    private static final int pageSize = Processor.pageSize;
    private static final char dbgProcess = 'a';
    private static final char dbgVM = 'v';
}
