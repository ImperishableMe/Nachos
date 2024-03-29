package nachos.userprog;

import nachos.machine.*;
import nachos.threads.*;
import nachos.userprog.*;
import nachos.vm.VMKernel;

import javax.crypto.Mac;
import java.io.EOFException;
import java.util.ArrayList;

/**
 * Encapsulates the state of a user process that is not contained in its
 * user thread (or threads). This includes its address translation state, a
 * file table, and information about the program being executed.
 *
 * <p>
 * This class is extended by other classes to support additional functionality
 * (such as additional syscalls).
 *
 * @see    nachos.vm.VMProcess
 * @see    nachos.network.NetProcess
 */
public class UserProcess {
    /**
     * Allocate a new process.
     */
    public UserProcess() {

        boolean intStatus = Machine.interrupt().disable();

        this.processID = ++UserKernel.totalCreatedProcesses;

        if (rootProcess == null)
            rootProcess = this;

        Machine.interrupt().restore(intStatus);

        childProcesses = new ArrayList<>();
        stdin = UserKernel.console.openForReading();
        stdout = UserKernel.console.openForWriting();
        isFinished = false;
    }

    /**
     * Allocate and return a new process of the correct class. The class name
     * is specified by the <tt>nachos.conf</tt> key
     * <tt>Kernel.processClassName</tt>.
     *
     * @return a new process of the correct class.
     */
    public static UserProcess newUserProcess() {
        return (UserProcess) Lib.constructObject(Machine.getProcessClassName());
    }

    /**
     * Execute the specified program with the specified arguments. Attempts to
     * load the program, and then forks a thread to run it.
     *
     * @param    name    the name of the file containing the executable.
     * @param    args    the arguments to pass to the executable.
     * @return    <tt>true</tt> if the program was successfully executed.
     */
    public boolean execute(String name, String[] args) {
        if (!load(name, args))
            return false;

        parentKThread = KThread.currentThread();
        aliveProcesses++;

        new UThread(this).setName(name).fork();

        return true;
    }

    /**
     * Save the state of this process in preparation for a context switch.
     * Called by <tt>UThread.saveState()</tt>.
     */
    public void saveState() {
    }

    /**
     * Restore the state of this process after a context switch. Called by
     * <tt>UThread.restoreState()</tt>.
     */
    public void restoreState() {
        Machine.processor().setPageTable(pageTable);
    }

    /**
     * Read a null-terminated string from this process's virtual memory. Read
     * at most <tt>maxLength + 1</tt> bytes from the specified address, search
     * for the null terminator, and convert it to a <tt>java.lang.String</tt>,
     * without including the null terminator. If no null terminator is found,
     * returns <tt>null</tt>.
     *
     * @param    vaddr    the starting virtual address of the null-terminated
     * string.
     * @param    maxLength    the maximum number of characters in the string,
     * not including the null terminator.
     * @return the string read, or <tt>null</tt> if no null terminator was
     * found.
     */
    public String readVirtualMemoryString(int vaddr, int maxLength) {
        Lib.assertTrue(maxLength >= 0);

        byte[] bytes = new byte[maxLength + 1];

        int bytesRead = readVirtualMemory(vaddr, bytes);

        Lib.debug('v', "bytesread " + bytesRead);

        for (int length = 0; length < bytesRead; length++) {
            if (bytes[length] == 0)
                return new String(bytes, 0, length);
        }

        return null;
    }

    /**
     * Transfer data from this process's virtual memory to all of the specified
     * array. Same as <tt>readVirtualMemory(vaddr, data, 0, data.length)</tt>.
     *
     * @param    vaddr    the first byte of virtual memory to read.
     * @param    data    the array where the data will be stored.
     * @return the number of bytes successfully transferred.
     */
    public int readVirtualMemory(int vaddr, byte[] data) {
        return readVirtualMemory(vaddr, data, 0, data.length);
    }

    /**
     * Transfer data from this process's virtual memory to the specified array.
     * This method handles address translation details. This method must
     * <i>not</i> destroy the current process if an error occurs, but instead
     * should return the number of bytes successfully copied (or zero if no
     * data could be copied).
     *
     * @param    vaddr    the first byte of virtual memory to read.
     * @param    data    the array where the data will be stored.
     * @param    offset    the first byte to write in the array.
     * @param    length    the number of bytes to transfer from virtual memory to
     * the array.
     * @return the number of bytes successfully transferred.
     */

    public boolean checkValidVPN (int vpn) {
        return !(vpn >= pageTable.length || pageTable[vpn] == null
                || !pageTable[vpn].valid);
    }

    public int readVirtualMemory(int vaddr, byte[] data, int offset,
                                 int length) {
        Lib.assertTrue(offset >= 0 && length >= 0 && offset + length <= data.length);

        byte[] memory = Machine.processor().getMemory();

        int readSoFar = 0;
        int endingVaddr = vaddr + length - 1;

        while (vaddr <= endingVaddr) {

            int vpn = pageFromAddress(vaddr);

            if (!checkValidVPN(vpn)) {
                Lib.debug('v', "invalid vpn " + vpn);
                return -1;
            }

            int startingOffset = offsetFromAddress(vaddr);
            int curPageEndingAddress = Math.min(endingVaddr,
                    makeAddress(vpn, pageSize - 1));
            int amount = curPageEndingAddress - vaddr + 1;

            TranslationEntry translationEntry = translateVirtualPage(vpn);
            int ppn = translationEntry.ppn;

            int startingMemoryAddress = makeAddress(ppn, startingOffset);
            System.arraycopy(memory, startingMemoryAddress, data, offset, amount);

            updateTLBEntry(vpn, false);

            vaddr += amount;
            offset += amount;
            readSoFar += amount;
        }

        return readSoFar;
    }

    /**
     * Transfer all data from the specified array to this process's virtual
     * memory.
     * Same as <tt>writeVirtualMemory(vaddr, data, 0, data.length)</tt>.
     *
     * @param    vaddr    the first byte of virtual memory to write.
     * @param    data    the array containing the data to transfer.
     * @return the number of bytes successfully transferred.
     */
    public int writeVirtualMemory(int vaddr, byte[] data) {
        return writeVirtualMemory(vaddr, data, 0, data.length);
    }

    /**
     * Transfer data from the specified array to this process's virtual memory.
     * This method handles address translation details. This method must
     * <i>not</i> destroy the current process if an error occurs, but instead
     * should return the number of bytes successfully copied (or zero if no
     * data could be copied).
     *
     * @param    vaddr    the first byte of virtual memory to write.
     * @param    data    the array containing the data to transfer.
     * @param    offset    the first byte to transfer from the array.
     * @param    length    the number of bytes to transfer from the array to
     * virtual memory.
     * @return the number of bytes successfully transferred.
     */
    public int writeVirtualMemory(int vaddr, byte[] data, int offset,
                                  int length) {
        Lib.assertTrue(offset >= 0 && length >= 0 && offset + length <= data.length);

        byte[] memory = Machine.processor().getMemory();

        int wroteSoFar = 0;
        int endingVaddr = vaddr + length - 1;

        while (vaddr <= endingVaddr) {
            int vpn = pageFromAddress(vaddr);

            if (!checkValidVPN(vpn)) {
                return -1;
            }

            int startingOffset = offsetFromAddress(vaddr);
            int curPageEndingAddress = Math.min(endingVaddr,
                    makeAddress(vpn, pageSize - 1));
            int amount = curPageEndingAddress - vaddr + 1;
            TranslationEntry translationEntry = translateVirtualPage(vpn);
            int ppn = translationEntry.ppn;

            Lib.assertTrue(translationEntry.valid);
            if (translationEntry.readOnly) {
                return -1;
            }

            int startingMemoryAddress = makeAddress(ppn, startingOffset);

            System.arraycopy(data, offset, memory, startingMemoryAddress, amount);

            updateTLBEntry(vpn, true);

            vaddr += amount;
            offset += amount;
            wroteSoFar += amount;
        }

        return wroteSoFar;
    }

    public void updateTLBEntry (int vpn, boolean isDirty) {

    }
    public TranslationEntry translateVirtualPage (int vpn){
        return pageTable[vpn];
    }

    public static int pageFromAddress(int address) {
        return (int) (((long) address & 0xFFFFFFFFL) / pageSize);
    }

    public static int offsetFromAddress(int address) {
        return (int) (((long) address & 0xFFFFFFFFL) % pageSize);
    }

    public static int makeAddress(int page, int offset) {

        Lib.assertTrue(offset >= 0 && offset < pageSize);
        return (page * pageSize) | offset;
    }

    /**
     * Load the executable with the specified name into this process, and
     * prepare to pass it the specified arguments. Opens the executable, reads
     * its header information, and copies sections and arguments into this
     * process's virtual memory.
     *
     * @param    name    the name of the file containing the executable.
     * @param    args    the arguments to pass to the executable.
     * @return    <tt>true</tt> if the executable was successfully loaded.
     */
    private boolean load(String name, String[] args) {
        Lib.debug(dbgProcess, "UserProcess.load(\"" + name + "\")");

        Lib.debug('v', "processname " + name);
        for (int i=0;i<args.length;i++)
        {
            Lib.debug('v', "args" + i + " =" + args[i]);
        }

        OpenFile executable = ThreadedKernel.fileSystem.open(name, false);
        if (executable == null) {
            Lib.debug(dbgProcess, "\topen failed");
            return false;
        }

        try {
            coff = new Coff(executable);
        } catch (EOFException e) {
            executable.close();
            Lib.debug(dbgProcess, "\tcoff load failed");
            return false;
        }

        // make sure the sections are contiguous and start at page 0
        numPages = 0;
        for (int s = 0; s < coff.getNumSections(); s++) {
            CoffSection section = coff.getSection(s);
            if (section.getFirstVPN() != numPages) {
                coff.close();
                Lib.debug(dbgProcess, "\tfragmented executable");
                return false;
            }
            numPages += section.getLength();
        }

        // make sure the argv array will fit in one page
        byte[][] argv = new byte[args.length][];
        int argsSize = 0;
        for (int i = 0; i < args.length; i++) {
            argv[i] = args[i].getBytes();
            // 4 bytes for argv[] pointer; then string plus one for null byte
            argsSize += 4 + argv[i].length + 1;
        }
        if (argsSize > pageSize) {
            coff.close();
            Lib.debug(dbgProcess, "\targuments too long");
            return false;
        }

        // program counter initially points at the program entry point
        initialPC = coff.getEntryPoint();

        // next comes the stack; stack pointer initially points to top of it
        numPages += stackPages;
        initialSP = numPages * pageSize;

        // and finally reserve 1 page for arguments
        numPages++;

        if (!loadSections())
            return false;

        // store arguments in last page
        loadCmdArgs(argv, args);

//        int entryOffset = (numPages - 1) * pageSize;
//        int stringOffset = entryOffset + args.length * 4;
//
//        this.argc = args.length;
//        this.argv = entryOffset;
//
//
//
//        for (int i = 0; i < argv.length; i++) {
//            byte[] stringOffsetBytes = Lib.bytesFromInt(stringOffset);
//            Lib.assertTrue(writeVirtualMemory(entryOffset, stringOffsetBytes) == 4);
//            entryOffset += 4;
//            Lib.assertTrue(writeVirtualMemory(stringOffset, argv[i]) ==
//                    argv[i].length);
//            stringOffset += argv[i].length;
//            Lib.assertTrue(writeVirtualMemory(stringOffset, new byte[]{0}) == 1);
//            stringOffset += 1;
//        }


//        for (int i = 0; i < argv.length; i++) {
//            byte[] stringOffsetBytes = Lib.bytesFromInt(stringOffset);
//            Lib.assertTrue(writeVirtualMemory(entryOffset, stringOffsetBytes) == 4);
//            entryOffset += 4;
//            Lib.assertTrue(writeVirtualMemory(stringOffset, argv[i]) ==
//                    argv[i].length);
//            stringOffset += argv[i].length;
//            Lib.assertTrue(writeVirtualMemory(stringOffset, new byte[]{0}) == 1);
//            stringOffset += 1;
//        }

        return true;
    }

    protected void loadCmdArgs(byte[][] argv, String[] args)
    {
        int entryOffset = (numPages - 1) * pageSize;
        int stringOffset = entryOffset + args.length * 4;

        this.argc = args.length;
        this.argv = entryOffset;



        for (int i = 0; i < argv.length; i++) {
            byte[] stringOffsetBytes = Lib.bytesFromInt(stringOffset);
            Lib.assertTrue(writeVirtualMemory(entryOffset, stringOffsetBytes) == 4);
            entryOffset += 4;
            Lib.assertTrue(writeVirtualMemory(stringOffset, argv[i]) ==
                    argv[i].length);
            stringOffset += argv[i].length;
            Lib.assertTrue(writeVirtualMemory(stringOffset, new byte[]{0}) == 1);
            stringOffset += 1;
        }
    }

    /**
     * Allocates memory for this process, and loads the COFF sections into
     * memory. If this returns successfully, the process will definitely be
     * run (this is the last step in process initialization that can fail).
     *
     * @return    <tt>true</tt> if the sections were successfully loaded.
     */
    protected boolean loadSections() {

        boolean intStatus = Machine.interrupt().disable();

        if (numPages > UserKernel.freePagePool.size()) {
            coff.close();
            Lib.debug(dbgProcess, "\tinsufficient physical memory");

            Machine.interrupt().restore(intStatus);

            return false;
        }

        Lib.debug(dbgProcess, "Process ID " + processID + " needs " + numPages + " pages\n");

        pageTable = new TranslationEntry[numPages];

        for (int vpn = 0; vpn < numPages; vpn++) {

            Lib.assertTrue(UserKernel.freePagePool.size() > 0, "Empty freePagePool");

            int ppn = UserKernel.freePagePool.poll();

            Lib.assertTrue(ppn >= 0 && ppn < Machine.processor().getNumPhysPages(),
                    "Invalid ppn!");

            pageTable[vpn] = new TranslationEntry(vpn,ppn, true , false , false, false);
        }

        // load sections
        for (int s = 0; s < coff.getNumSections(); s++) {
            CoffSection section = coff.getSection(s);

            Lib.debug(dbgProcess, "\tinitializing " + section.getName()
                    + " section (" + section.getLength() + " pages)");

            for (int i = 0; i < section.getLength(); i++) {
                int vpn = section.getFirstVPN() + i;

                pageTable[vpn].readOnly = section.isReadOnly();

                section.loadPage(i, pageTable[vpn].ppn);
            }
        }

        Machine.interrupt().restore(intStatus);

        return true;
    }

    /**
     * Release any resources allocated by <tt>loadSections()</tt>.
     */
    protected void unloadSections() {

        for (TranslationEntry entry : pageTable) {

            Lib.assertTrue(! UserKernel.freePagePool.contains(entry.ppn),
                    "Page Allocated multiple time");

            UserKernel.freePagePool.add(entry.ppn);
        }
    }

    /**
     * Initialize the processor's registers in preparation for running the
     * program loaded into this process. Set the PC register to point at the
     * start function, set the stack pointer register to point at the top of
     * the stack, set the A0 and A1 registers to argc and argv, respectively,
     * and initialize all other registers to 0.
     */
    public void initRegisters() {
        Processor processor = Machine.processor();

        // by default, everything's 0
        for (int i = 0; i < processor.numUserRegisters; i++)
            processor.writeRegister(i, 0);

        // initialize PC and SP according
        processor.writeRegister(Processor.regPC, initialPC);
        processor.writeRegister(Processor.regSP, initialSP);

        // initialize the first two argument registers to argc and argv
        processor.writeRegister(Processor.regA0, argc);
        processor.writeRegister(Processor.regA1, argv);
    }

    /**
     * Handle the halt() system call.
     */
    private int handleHalt() {

        if (rootProcess != this)
            return 1;


        Machine.halt();
        Lib.assertNotReached("Machine.halt() did not halt machine!");
        return 0;
    }
    private int handleRead(int fd, int buffAddress, int count) {
        if (fd != 0)
            return -1;
        if (buffAddress < 0 || buffAddress >= numPages * pageSize)
            return -1;
        if (count < 0)
            return -1;


        byte[] data = new byte[count];

        int successfulRead = stdin.read(data, 0, count);
        // check if it is read only memory
        return writeVirtualMemory(buffAddress, data, 0, successfulRead);
    }

    private int handleWrite(int fd, int buffAddress, int count) {
        if (fd != 1)
            return -1;
        if (buffAddress < 0 || buffAddress >= numPages * pageSize)
            return -1;
        if (count < 0)
            return -1;

        byte[] data = new byte[count];

        int len = readVirtualMemory(buffAddress, data, 0, count);

        stdout.write(data, 0, len);
        return 0;
    }

    private int handleExec (int fileNameVaddr, int argc, int argvVaddr) {
        Lib.debug(dbgProcess, "in exec with filename" +
                Processor.pageFromAddress(fileNameVaddr));

        String processName = readVirtualMemoryString(fileNameVaddr, 256);

        Lib.debug('v', "processName " + processName);

        if (processName == null || !processName.endsWith(".coff")) {
            return -1;
        }

        String argv[] = new String[argc];


        for(int i = 0; i < argc; i++) {
            byte[] startingAddrBytes = new byte[4];
            readVirtualMemory(argvVaddr, startingAddrBytes);

            Lib.debug('v', "readMem " + Lib.bytesToInt(startingAddrBytes,0));

            int startingAddr = Lib.bytesToInt(startingAddrBytes, 0);
            argv[i] = readVirtualMemoryString(startingAddr, 256);

            if(argv[i] == null)
                argv[i] = "";

            Lib.debug('v', "processId: " + processID +
                    "argv" + i + " :" + argv[i]);

            argvVaddr += 4;


        }

        UserProcess child = newUserProcess();
        int childID = -1;

        if (child.execute(processName, argv)) {
            childProcesses.add(child);
            childID = child.processID;
            child.parent = this;
        }


        return childID;
    }

    private int handleJoin (int childProcessID, int statusPointer) {
        boolean status1 = Machine.interrupt().disable();

        Lib.debug(dbgProcess,"in join with " + childProcessID + " " + statusPointer );

        UserProcess childProcess = null;
        for (UserProcess child : childProcesses) {
            if (child.processID == childProcessID)
                childProcess = child;
        }
        if (childProcess == null) {
            return -1;
        }

        if (!childProcess.isFinished) {
            childProcess.joined = true;
            KThread.sleep();
        }

        Lib.assertTrue(childProcess.isFinished, "Joined process is not finished");

        writeVirtualMemory(statusPointer, Lib.bytesFromInt(childProcess.exitStatus));

        childProcesses.remove(childProcess); // disown this child

        Machine.interrupt().restore(status1);

        if (childProcess.normallyExited)
            return 1;
        else
            return 0;
    }


    private void handleExit (int status) {
        killProcess(status, true);
    }

    public void killProcess (int status, boolean normallyExited) {

        boolean status1 = Machine.interrupt().disable();

        for (UserProcess child :  this.childProcesses) {
            child.parent = null;
        }

        isFinished = true;

        Lib.debug(dbgProcess, "Before killing process " + processID
                + " pagePool had " + UserKernel.freePagePool.size() + " pages\n");


        exitStatus = status;
        this.normallyExited = normallyExited;

        stdin.close();
        stdout.close();

        unloadSections();

        if (joined) {
            parentKThread.ready();
        }

        //Lib.debug(dbgProcess, "Are you here?\n");

        aliveProcesses--;
        Lib.assertTrue(aliveProcesses >= 0,
                "Alive count is wrong!");


        Lib.debug(dbgProcess, "Process ID " + processID + " had " + numPages + " pages\n");
        Lib.debug(dbgProcess, processID + " th process exiting with status " + status + '\n');

        Lib.debug(dbgProcess, "After killing process " + processID
                + " pagePool had " + UserKernel.freePagePool.size() + " pages\n");

        Lib.debug(dbgProcess, "AliveProcesses " + aliveProcesses + "\n");

        if (aliveProcesses == 0)
            Kernel.kernel.terminate();

        KThread.finish();
    }

    private static final int
            syscallHalt = 0,
            syscallExit = 1,
            syscallExec = 2,
            syscallJoin = 3,
            syscallCreate = 4,
            syscallOpen = 5,
            syscallRead = 6,
            syscallWrite = 7,
            syscallClose = 8,
            syscallUnlink = 9;

    /**
     * Handle a syscall exception. Called by <tt>handleException()</tt>. The
     * <i>syscall</i> argument identifies which syscall the user executed:
     *
     * <table>
     * <tr><td>syscall#</td><td>syscall prototype</td></tr>
     * <tr><td>0</td><td><tt>void halt();</tt></td></tr>
     * <tr><td>1</td><td><tt>void exit(int status);</tt></td></tr>
     * <tr><td>2</td><td><tt>int  exec(char *name, int argc, char **argv);
     * 								</tt></td></tr>
     * <tr><td>3</td><td><tt>int  join(int pid, int *status);</tt></td></tr>
     * <tr><td>4</td><td><tt>int  creat(char *name);</tt></td></tr>
     * <tr><td>5</td><td><tt>int  open(char *name);</tt></td></tr>
     * <tr><td>6</td><td><tt>int  read(int fd, char *buffer, int size);
     * 								</tt></td></tr>
     * <tr><td>7</td><td><tt>int  write(int fd, char *buffer, int size);
     * 								</tt></td></tr>
     * <tr><td>8</td><td><tt>int  close(int fd);</tt></td></tr>
     * <tr><td>9</td><td><tt>int  unlink(char *name);</tt></td></tr>
     * </table>
     *
     * @param    syscall    the syscall number.
     * @param    a0    the first syscall argument.
     * @param    a1    the second syscall argument.
     * @param    a2    the third syscall argument.
     * @param    a3    the fourth syscall argument.
     * @return the value to be returned to the user.
     */
    public int handleSyscall(int syscall, int a0, int a1, int a2, int a3) {
        switch (syscall) {
            case syscallHalt:
                return handleHalt();

            case syscallWrite:
                return handleWrite(a0, a1, a2);

            case syscallRead:
                return handleRead(a0, a1, a2);

            case syscallExit:
                handleExit(a0);
                break;
            case syscallExec:
                return handleExec(a0, a1, a2);

            case syscallJoin:
                return handleJoin(a0, a1);

            default:
                Lib.debug(dbgProcess, "Unknown syscall " + syscall);

                killProcess(1, false); // kernel is killing it

                Lib.assertNotReached("Unknown system call!");
        }
        return 0;
    }

    /**
     * Handle a user exception. Called by
     * <tt>UserKernel.exceptionHandler()</tt>. The
     * <i>cause</i> argument identifies which exception occurred; see the
     * <tt>Processor.exceptionZZZ</tt> constants.
     *
     * @param    cause    the user exception that occurred.
     */
    public void handleException(int cause) {
//        boolean intStatus = Machine.interrupt().disable();
        Processor processor = Machine.processor();

        switch (cause) {
            case Processor.exceptionSyscall:
                int result = handleSyscall(processor.readRegister(Processor.regV0),
                        processor.readRegister(Processor.regA0),
                        processor.readRegister(Processor.regA1),
                        processor.readRegister(Processor.regA2),
                        processor.readRegister(Processor.regA3)
                );
                processor.writeRegister(Processor.regV0, result);
                processor.advancePC();
                break;

            default:
                int vaddr = processor.readRegister(Processor.regBadVAddr);
                int vpn = Processor.pageFromAddress(vaddr);

                Lib.debug(dbgProcess, processID + " process  vpn : " + vpn);
                Lib.debug(dbgProcess, "Unexpected exception: " +
                        Processor.exceptionNames[cause]);

                killProcess(2, false);

                Lib.assertNotReached("Unexpected exception");
        }
//        Machine.interrupt().restore(intStatus);
    }

    public static void printState()
    {
        for (int i = 0; i < Machine.processor().getTLBSize(); i++)
        {
            Lib.debug('v', Machine.processor().readTLBEntry(i).toString());
        }

        Lib.debug('v', VMKernel.invertedPageTable.toString());
    }

    private OpenFile stdin = null;

    private OpenFile stdout = null;

    /**
     * The program being run by this process.
     */
    protected Coff coff;

    /**
     * This process's page table.
     */
    protected TranslationEntry[] pageTable;
    /**
     * The number of contiguous pages occupied by the program.
     */
    protected int numPages;

    /**
     * The number of pages in the program's stack.
     */
    protected final int stackPages = 8;

    private int initialPC, initialSP;
    protected int argc, argv;

    protected int processID;
    protected UserProcess parent;
    protected ArrayList<UserProcess> childProcesses;
    protected boolean isFinished;
    protected KThread parentKThread;
    protected boolean joined = false;
    protected int exitStatus;

    public int getProcessID() {
        return processID;
    }

    protected boolean normallyExited;

    protected static int aliveProcesses = 0;

    protected static UserProcess rootProcess;

    protected static final int pageSize = Processor.pageSize;
    private static final char dbgProcess = 'a';
}
