package dla.cluster

import chisel3._
import chisel3.experimental.{DataMirror, Direction}
import chisel3.util._
import dla.pe.SPadSizeConfig

class ClusterGroup(debug: Boolean) extends HasConnectAllExpRdModule with ClusterConfig {
  val io: ClusterGroupIO = IO(new ClusterGroupIO)
  protected val peCluster: PEClusterIO = Module(new PECluster(debug)).io
  protected val glbCluster: GLBClusterIO = Module(new GLBCluster(debug)).io
  protected val routerCluster: RouterClusterIO = Module(new RouterCluster(debug)).io
  protected val clusterCtrl: ClusterGroupControllerIO = Module(new ClusterGroupController(debug = debug)).io
  // suggest name
  // connections of control path
  require(DataMirror.directionOf(io.ctrlPath.routerClusterCtrl.inActCtrlSel.inDataSel) == Direction.Input, "it should be input")
  // routerCluster control path
  routerCluster.ctrlPath.iRIO.foreach(_ <> io.ctrlPath.routerClusterCtrl.inActCtrlSel)
  routerCluster.ctrlPath.wRIO.foreach(_ <> io.ctrlPath.routerClusterCtrl.weightCtrlSel)
  routerCluster.ctrlPath.pSumRIO.foreach(_ <> io.ctrlPath.routerClusterCtrl.pSumCtrlSel)
  // peCluster control path
  // true for broad-cast
  peCluster.ctrlPath.inActCtrlSel <> io.ctrlPath.peClusterCtrl.inActSel
  peCluster.ctrlPath.pSumCtrlSel.inDataSel := io.ctrlPath.peClusterCtrl.pSumInSel
  /** we can disable the outDataSel, as output of pSum is connected directly to router and outside*/
  peCluster.ctrlPath.pSumCtrlSel.outDataSel := DontCare // unused
  // doEn
  peCluster.ctrlPath.doEn := clusterCtrl.peCtrlIO.peLoadEn // to load inAct and weight // TODO: check
  peCluster.ctrlPath.pSumLoadEn := clusterCtrl.peCtrlIO.pSumLoadEn
  // GLB control path
  glbCluster.ctrlPath.pSumIO.zip(clusterCtrl.glbPSumCtrlIOs).foreach({ case (o, o1) => o <> o1})
  /** glbCluster.ctrlPath.pSumIO.writeOrRead: true when glbLoad or write back from PEArray */
  glbCluster.ctrlPath.inActIO.zip(clusterCtrl.glbInActCtrlIOs).foreach({ case (o, o1) => o <> o1})
  // clusterCtrl
  clusterCtrl.topIO.readOutPSum := io.ctrlPath.readOutPSum
  clusterCtrl.topIO.cgEnable := io.ctrlPath.doMacEn // TODO: check
  clusterCtrl.allPSumAddFin := peCluster.ctrlPath.allPSumAddFin
  clusterCtrl.allCalFin := peCluster.ctrlPath.allCalFin
  io.ctrlPath.calFin := clusterCtrl.topIO.calFin
  io.ctrlPath.peWeightLoadEn := clusterCtrl.peCtrlIO.peLoadEn
  io.ctrlPath.glbInActLoadEn := clusterCtrl.glbInActLoadEn
  // connections of data path
  // input activations
  for (i <- 0 until inActRouterNum) {
    /** port 0  for GLB Cluster, 1 for north, 2 for south, 3 for horizontal*/
    // top connections
    glbCluster.dataPath.inActIO(i).inIOs <> io.dataPath.glbDataPath.inActIO(i).inIOs
    io.dataPath.glbDataPath.inActIO(i).outIOs <> DontCare // disable this IO
    for (j <- 1 until inActPortNum) {
      routerCluster.dataPath.routerData.iRIO(i).inIOs(j) <> io.dataPath.cgDataPath.iRIO(i).inIOs(j)
      io.dataPath.cgDataPath.iRIO(i).outIOs(j) <> routerCluster.dataPath.routerData.iRIO(i).outIOs(j)
    }
    /** the inActRouter in port zero comes from GLB, so we can disable the GroupIO's inAct port zero*/
    io.dataPath.cgDataPath.iRIO(i).inIOs.head <> DontCare
    /** the inActRouter out port zero connected to peCluster, so disable the GroupIO's inAct port zero*/
    io.dataPath.cgDataPath.iRIO(i).outIOs.head <> DontCare
    // inner connections
    routerCluster.dataPath.routerData.iRIO(i).inIOs.head <> glbCluster.dataPath.inActIO(i).outIOs
    peCluster.dataPath.inActIO(i) <> routerCluster.dataPath.routerData.iRIO(i).outIOs.head
  }
  // weight
  require(io.dataPath.cgDataPath.wRIO.head.inIOs.length == 2, "the number of ports in weight router should be 2 " +
    "or you should correct the code")
  for (i <- 0 until weightRouterNum) {
    /** input: port 0 from GLB Cluster, 1 from neighboring weight router
      * output: port 0 to PE Cluster, 1 to neighboring weight router
      * */
    // top connections
    // GLB Cluster
    glbCluster.dataPath.weightIO(i).inIOs <> io.dataPath.glbDataPath.weightIO(i).inIOs
    io.dataPath.glbDataPath.weightIO(i).outIOs <> DontCare // disable it, as we don't output weight via GLB
    routerCluster.dataPath.routerData.wRIO(i).inIOs.last <> io.dataPath.cgDataPath.wRIO(i).inIOs.last
    // Router Cluster
    // the corresponding port of weightRouter connected GLB, so disable it
    io.dataPath.cgDataPath.wRIO(i).inIOs.head <> DontCare
    io.dataPath.cgDataPath.wRIO(i).outIOs.last <> routerCluster.dataPath.routerData.wRIO(i).outIOs.last
    // the corresponding port of weightRouter connected GLB, so disable it
    io.dataPath.cgDataPath.wRIO(i).outIOs.head <> DontCare
    // inner connections
    routerCluster.dataPath.routerData.wRIO(i).inIOs.head <> glbCluster.dataPath.weightIO(i).outIOs
    peCluster.dataPath.weightIO(i) <> routerCluster.dataPath.routerData.wRIO(i).outIOs.head
  }
  // partial sum
  require(io.dataPath.cgDataPath.pSumRIO.head.inIOs.length == 3, "the number of ports " +
    "in partial sum router should be 3 or you should correct the code")
  for (i <- 0 until pSumRouterNum) {
    // 0 for PE Cluster, 1 for GLB Cluster, 2 for vertical
    // top connections
    io.dataPath.glbDataPath.pSumIO(i).outIOs.bits := glbCluster.dataPath.pSumIO(i).outIOs.bits
    io.dataPath.glbDataPath.pSumIO(i).outIOs.valid := glbCluster.dataPath.pSumIO(i).outIOs.valid &&
      io.ctrlPath.readOutPSum // when need read out PSum from GLB to outside
    routerCluster.dataPath.routerData.pSumRIO(i).inIOs(1).bits := glbCluster.dataPath.pSumIO(i).outIOs.bits
    routerCluster.dataPath.routerData.pSumRIO(i).inIOs(1).valid := glbCluster.dataPath.pSumIO(i).outIOs.valid &&
      clusterCtrl.pSumAdd // when need accumulate pSum, then we need to read out pSum from GLB
    glbCluster.dataPath.pSumIO(i).outIOs.ready := (routerCluster.dataPath.routerData.pSumRIO(i).inIOs(1).ready &&
      clusterCtrl.pSumAdd) || (io.dataPath.glbDataPath.pSumIO(i).outIOs.ready && io.ctrlPath.readOutPSum)
    /** pSumCtrlSel.outDataSel:
      * true then PE cluster's pSum will be sent to GLB,
      * false then it will be sent to vertical CGroup
      * */
    when (io.ctrlPath.routerClusterCtrl.pSumCtrlSel.outDataSel) {
      glbCluster.dataPath.pSumIO(i).inIOs <> routerCluster.dataPath.routerData.pSumRIO(i).outIOs(1)
      io.dataPath.glbDataPath.pSumIO(i).inIOs.ready := false.B
    } .otherwise {
      glbCluster.dataPath.pSumIO(i).inIOs <> io.dataPath.glbDataPath.pSumIO(i).inIOs
      routerCluster.dataPath.routerData.pSumRIO(i).outIOs(1).ready := false.B
    }
    routerCluster.dataPath.routerData.pSumRIO(i).inIOs.last <> io.dataPath.cgDataPath.pSumRIO(i).inIOs.last
    // only need the final one to receive data outside, other two comes from inner
    io.dataPath.cgDataPath.pSumRIO(i).inIOs.take(2).foreach(_ <> DontCare)
    io.dataPath.cgDataPath.pSumRIO(i).outIOs.last <> routerCluster.dataPath.routerData.pSumRIO(i).outIOs.last
    // only need the final one to receive data outside, other two comes from inner
    io.dataPath.cgDataPath.pSumRIO(i).outIOs.take(2).foreach(_ <> DontCare)
    // pSumData from or to vertical PE Arrays
    io.dataPath.pSumDataVerticalIOs.outIOs(i) <> peCluster.dataPath.pSumIO.outIOs(i)
    peCluster.dataPath.pSumDataFromSouthernIO(i) <> io.dataPath.pSumDataVerticalIOs.inIOs(i)
    // inner connections
    // peCluster.dataPath.pSumIO.outIOs(i) transfer partial sum not only to outside cluster group, but also to router
    routerCluster.dataPath.routerData.pSumRIO(i).inIOs.head <> peCluster.dataPath.pSumIO.outIOs(i)
    // peCluster.dataPath.pSumIO.inIOs(i) receive data directly from pSumRouter
    peCluster.dataPath.pSumIO.inIOs(i) <> routerCluster.dataPath.routerData.pSumRIO(i).outIOs.head
  }
}

class ClusterGroupController(debug: Boolean) extends Module with GNMFCS2Config with ClusterSRAMConfig with SPadSizeConfig{
  val io: ClusterGroupControllerIO = IO(new ClusterGroupControllerIO)
  protected val configFixValSeq = Seq(G2, N2, M2, F2, C2, S2) // FIXME: read that from outside
  protected val configIncWireSeq: Seq[Bool] = Seq.fill(6){Wire(Bool())}
  configIncWireSeq.zipWithIndex.foreach({ case (bool, i) => bool.suggestName(s"configIncWire$i")})
  protected val (configG2Val, configG2Wrap) = Counter(configIncWireSeq.head, configFixValSeq.head)
  protected val (configN2Val, configN2Wrap) = Counter(configIncWireSeq(1), configFixValSeq(1))
  protected val (configM2Val, configM2Wrap) = Counter(configIncWireSeq(2), configFixValSeq(2))
  protected val (configF2Val, configF2Wrap) = Counter(configIncWireSeq(3), configFixValSeq(3))
  protected val (configC2Val, configC2Wrap) = Counter(configIncWireSeq(4), configFixValSeq(4))
  protected val (configS2Val, configS2Wrap) = Counter(configIncWireSeq(5), configFixValSeq(5))
  protected val configWrapWireSeq = Seq(configG2Wrap, configN2Wrap, configM2Wrap, configF2Wrap, configC2Wrap, configS2Wrap)
  //private val configValWireVec = Seq(configG2Val, configN2Val, configM2Val, configF2Val, configC2Val, configS2Val)
  // 0g, 1n, 2m, 3f, 4c, 5s
  protected val inActReadTwoReg: Bool = RegInit(false.B) // for inAct, which needs former and later data read
  inActReadTwoReg.suggestName("inActReadTwoReg")
  protected val glbInActWriteFinReg: Seq[Bool] = Seq.fill(inActSRAMNum){RegInit(false.B)}
  glbInActWriteFinReg.zipWithIndex.foreach({ case (bool, i) => bool.suggestName(s"glbInActWriteFin$i")})
  protected val glbInActReadFinReg: Seq[Bool] = Seq.fill(inActSRAMNum){RegInit(false.B)}
  glbInActReadFinReg.zipWithIndex.foreach({ case (bool, i) => bool.suggestName(s"glbInActReadFin$i")})
  protected val glbPSumWriteFinReg: Seq[Bool] = Seq.fill(pSumSRAMNum){RegInit(false.B)}
  // true when inAct data have loaded into GLB from off chip
  protected val glbInActWriteFinWire: Bool = glbInActWriteFinReg.reduce(_ && _)
  protected val glbInActReadFinOnceWire: Bool = glbInActReadFinReg.reduce(_ && _)
  protected val glbInActReadFinWire: Bool = glbInActReadFinOnceWire && inActReadTwoReg
  // true when pSum data have loaded into GLB from PECluster
  protected val glbPSumLoadFinWire: Bool = glbPSumWriteFinReg.reduce(_ && _)
  glbInActWriteFinReg.zip(io.glbInActCtrlIOs.map(x => x.writeIO.done)).foreach({ case (reg, doneIO) =>
    reg := Mux(glbInActWriteFinWire, false.B, Mux(doneIO, true.B, reg))
  })
  glbInActReadFinReg.zip(io.glbInActCtrlIOs.map(x => x.readIO.done)).foreach({ case (reg, doneIO) =>
    reg := Mux(glbInActReadFinOnceWire, false.B, Mux(doneIO, true.B, reg))
  })
  inActReadTwoReg := Mux(glbInActReadFinOnceWire, !inActReadTwoReg, inActReadTwoReg)
  glbPSumWriteFinReg.zip(io.glbPSumCtrlIOs.map(x => x.writeIO.done)).foreach({case (reg, doneIO) =>
    reg := Mux(glbPSumLoadFinWire, false.B, Mux(doneIO, true.B, reg))
  })
  /** cluster group state machine
    * cgLoadGLB: load inAct and PSum from outside CG into GLBCluster
    * cgLoadPE: load inAct, weight from outside PECluster (from GLB and outside CG)
    * cgCal: PE doing computations
    * cgRead: PE read PSum into the tails of PEArray to
    * accumulate them and get out put PSum
    * */
  protected val cgIdle :: cgLoadGLB :: cgLoadPE :: cgCal :: cgRead :: Nil = Enum(5)
  protected val cgStateReg: UInt = RegInit(cgIdle)
  cgStateReg.suggestName("cgStateReg")
  switch (cgStateReg) {
    is (cgIdle) {
      when (io.topIO.cgEnable) {  // when doEn ClusterGroup
        cgStateReg := cgLoadGLB
      }
    }
    is (cgLoadGLB) {
      /** when inAct data and address have stored into GLB, then begin load data into PE*/
      when (glbInActWriteFinWire) {
        cgStateReg := cgLoadPE
      }
    }
    is (cgLoadPE) {
      /** when inAct write finish, then all PEs have finish read inAct*/
      when (glbInActReadFinWire) {
        cgStateReg := cgCal
      }
    }
    is (cgCal) {
      /** every time s2Inc, that means cal finish*/
      when (configIncWireSeq(5)) {
        /** when c2Wrap, we need to read out PSum without change the values of f2, etc. */
        when(configWrapWireSeq(4)) {
          cgStateReg := cgRead
        }.otherwise { // or we need to load new InAct and Weight BUT PSum
          cgStateReg := cgLoadPE
        }
      }
    }
    is (cgRead) {
      when (glbPSumLoadFinWire) {
        /** after GLB, read out all PSum from the head of each column*/
        when (configG2Wrap) { // when g2 wrap, then finish current takes
          cgStateReg := cgIdle
        } .otherwise { // or , load next c2*s2
          cgStateReg := cgLoadPE
        }
      }
    }
  }
  // logic
  protected val c2WrapReg: Bool = RegInit(false.B)
  when (configWrapWireSeq(4)) { // c2 warp, then f2 will increase
    c2WrapReg := true.B
  } .elsewhen (configIncWireSeq(3)) { // after use this value
    c2WrapReg := false.B
  } .otherwise {
    c2WrapReg := c2WrapReg
  }
  configIncWireSeq(5) := io.allCalFin
  configIncWireSeq(4) := configWrapWireSeq(5) // s2Wrap, c2Inc
  configIncWireSeq(3) := c2WrapReg && io.allPSumAddFin // c2Wrap and read finish all PSum
  for (i <- 1 until 4) {
    configIncWireSeq(i - 1) := configWrapWireSeq(i)
  }
  // Outputs
  protected val pSumAdrL2: UInt = configG2Val*(N2*M2*F2).U + configN2Val*(M2*F2).U + configM2Val*F2.U + configF2Val
  protected val inActReadAdrL2: UInt = configG2Val*(N2*C2*(F2+S2)).U + configN2Val*(C2*(F2+S2)).U +
    configC2Val*(F2+S2).U + configF2Val + configS2Val
  protected val pSumWriteAdrL4Reg: UInt = RegInit(0.U(log2Ceil(pSumDataSPadSize).W))
  protected val pSumReadAdrL4Reg: UInt = RegInit(0.U(log2Ceil(pSumDataSPadSize).W))
  protected val cgLoadGLBWire: Bool = cgStateReg === cgLoadGLB
  protected val cgLoadPEWire: Bool = cgStateReg === cgLoadPE
  protected val cgReadWire: Bool = cgStateReg === cgRead
  // FIXME: need reset, need fire(), check whether .head is correct
  pSumReadAdrL4Reg := Mux(io.glbPSumCtrlIOs.head.readIO.done, pSumReadAdrL4Reg + 1.U, pSumReadAdrL4Reg)
  pSumWriteAdrL4Reg := Mux(io.glbPSumCtrlIOs.head.writeIO.done, pSumWriteAdrL4Reg + 1.U, pSumWriteAdrL4Reg)
  io.glbPSumCtrlIOs.zipWithIndex.foreach({case (x,idx) =>
    x.writeIO.adr := pSumAdrL2 + pSumWriteAdrL4Reg
    x.writeIO.enable := cgReadWire && !glbPSumWriteFinReg(idx) // TODO:use cgLoadGLBWire to load PSum at the beginning
    x.readIO.adr := pSumAdrL2 + pSumReadAdrL4Reg
    /** PSum will be read out from GLB to PE for accumulation or from GLB to outside as a result*/
    x.readIO.enable := (cgReadWire && !glbPSumWriteFinReg(idx)) || io.topIO.readOutPSum
  })
  io.glbInActCtrlIOs.zipWithIndex.foreach({case (x, idx) =>
    x.writeIO.enable := RegNext(io.topIO.cgEnable && cgStateReg === cgIdle, false.B)
    x.writeIO.adr := DontCare
    x.readIO.adr := inActReadAdrL2 // FIXME: change the address while reading later
    /** each GLB inAct's read enable will be true until the corresponding PEs finish reading */
    x.readIO.enable := cgLoadPEWire && !glbInActReadFinReg(idx)
  })
  io.peCtrlIO.pSumLoadEn := RegNext(configIncWireSeq(5) && configWrapWireSeq(4) && cgStateReg === cgCal)
  io.peCtrlIO.peLoadEn := cgLoadPEWire
  io.pSumAdd := cgReadWire
  io.glbInActLoadEn := cgLoadGLBWire
  io.topIO.calFin := cgReadWire && configG2Wrap
  if (debug) {
    io.debugIO.cgState := cgStateReg
    io.debugIO.inActWriteFinVecIO := glbInActWriteFinReg
    io.debugIO.inActReadFinVecIO := glbInActReadFinReg
    io.debugIO.pSumWriteFinVecIO := glbPSumWriteFinReg
  } else {
    io.debugIO <> DontCare
  }
}

class ClusterGroupControllerIO extends Bundle with ClusterSRAMConfig with GNMFCS2Config {
  val glbPSumCtrlIOs: Vec[SRAMCommonCtrlIO] = Vec(pSumSRAMNum, Flipped(new SRAMCommonCtrlIO(theMemSize = pSumSRAMSize)))
  val glbInActCtrlIOs: Vec[SRAMCommonCtrlIO] = Vec(inActSRAMNum, Flipped(new SRAMCommonCtrlIO(theMemSize = inActStreamNum)))
  val peCtrlIO = new Bundle {
    val peLoadEn: Bool = Output(Bool())
    val pSumLoadEn: Bool = Output(Bool())
  }
  val allPSumAddFin: Bool = Input(Bool()) // all columns of PE have finished accumulated PSum
  val allCalFin: Bool = Input(Bool()) // all PEs have finished the MAC computation
  val pSumAdd: Bool = Output(Bool())
  val topIO = new Bundle {
    val readOutPSum: Bool = Input(Bool())
    val cgEnable: Bool = Input(Bool())
    val calFin: Bool = Output(Bool()) // current layer has finished all the computations
  }
  val glbInActLoadEn: Bool = Output(Bool())
  val debugIO = new Bundle {
    val cgState: UInt = Output(UInt(3.W))
    val inActWriteFinVecIO: Vec[Bool] = Output(Vec(inActSRAMNum, Bool()))
    val inActReadFinVecIO: Vec[Bool] = Output(Vec(inActSRAMNum, Bool()))
    val pSumWriteFinVecIO: Vec[Bool] = Output(Vec(pSumSRAMNum, Bool()))
  }
}
