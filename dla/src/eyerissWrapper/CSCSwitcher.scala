package dla.eyerissWrapper

import chisel3._
import chisel3.util._
import dla.cluster.GNMFCS2Config
import dla.pe.{CSCStreamIO, MCRENFConfig, PESizeConfig}

class CSCSwitcherDebugIO extends Bundle with PESizeConfig {
  val firstNoneZero: Bool = Output(Bool())
  val zeroColReg: Bool = Output(Bool())
  val cscAdrReg: UInt = Output(UInt(5.W))
  val columnCounter: UInt = Output(UInt(5.W))
  val endFlag: Bool = Output(Bool())
  val oneVecFin: Bool = Output(Bool())
  val allVecFin: Bool = Output(Bool())
  val oneColFin: Bool = Output(Bool())
  val currentRow: UInt = Output(UInt(cscCountWidth.W))
  val currentStreamNum: UInt = Output(UInt(5.W))
}

class CSCSwitcherCtrlIO(private val lgVectorNum: Int) extends Bundle with MCRENFConfig {
  /** use matrix height and width to increase and wrap csc address and count reg */
  require(scala.math.max(inActMatrixHeight, weightMatrixHeight) <= scala.math.pow(2, 5))
  require(scala.math.max(inActMatrixWidth, weightMatrixWidth) <= scala.math.pow(2, 5))
  val matrixHeight: UInt = Input(UInt(5.W)) // TODO: check the width
  val matrixWidth: UInt = Input(UInt(5.W))
  val vectorNum: UInt = Input(UInt(lgVectorNum.W))
}

class CSCSwitcherIO(private val adrWidth: Int) extends Bundle
  with PESizeConfig with GNMFCS2Config {
  private val lgVectorNum = if (adrWidth == inActAdrWidth) log2Ceil(inActStreamNum) else log2Ceil(weightStreamNum)
  val inData: DecoupledIO[UInt] = Flipped(Decoupled(UInt(cscDataWidth.W)))
  val outData = new CSCStreamIO(adrWidth = adrWidth, dataWidth = cscDataWidth + cscCountWidth)
  val ctrlPath = new CSCSwitcherCtrlIO(lgVectorNum = lgVectorNum)
  val debugIO = new CSCSwitcherDebugIO
}

class CSCSwitcher(private val adrWidth: Int, debug: Boolean) extends Module
  with PESizeConfig with GNMFCS2Config {
  val io: CSCSwitcherIO = IO(new CSCSwitcherIO(adrWidth = adrWidth))
  private val dataWidth = cscDataWidth + cscCountWidth
  private val zeroCode = if (adrWidth == inActAdrWidth) inActZeroColumnCode else weightZeroColumnCode
  private val lgVectorNum = if (adrWidth == inActAdrWidth) log2Ceil(inActStreamNum) else log2Ceil(weightStreamNum)
  // TODO: generate SIMD csc for weight
  protected val inData: DecoupledIO[UInt] = Queue(io.inData, fifoSize, flow = true, pipe = true)
  protected val outAdr: DecoupledIO[UInt] = Wire(Decoupled(UInt(adrWidth.W)))
  protected val outData: DecoupledIO[UInt] = Wire(Decoupled(UInt(dataWidth.W)))
  protected val cscCountReg: UInt = RegInit(0.U(cscCountWidth.W))
  protected val cscCountPlusOne: UInt = cscCountReg + 1.U
  protected val cscAdrReg: UInt = RegInit(0.U(adrWidth.W))
  protected val cscAdrPlusOne: UInt = cscAdrReg + 1.U
  protected val columnCounter: UInt = RegInit(0.U(5.W))
  protected val columnCounterPlusOne: UInt = columnCounter + 1.U
  protected val zeroColReg: Bool = RegInit(true.B) // true when current column contains zero only
  /** [[vectorNumCounter]] will count current padNumber.
    * TODO: change [[lgVectorNum]] to SRAMSize/padSize */
  protected val vectorNumCounter: UInt = RegInit(0.U(lgVectorNum.W))
  protected val vectorNumPlusOne: UInt = vectorNumCounter + 1.U
  protected val meetNoneZeroWire: Bool = Wire(Bool())
  protected val oneColFinWire: Bool = Wire(Bool())
  protected val oneMatrixFinWire: Bool = Wire(Bool())
  protected val oneVectorFinRegNext: Bool = RegNext(oneColFinWire && oneMatrixFinWire) // true when process one pad data
  protected val oneStreamFinRegNext: Bool = RegNext(oneVectorFinRegNext && (io.ctrlPath.vectorNum === vectorNumPlusOne))
  /** when cscCountReg equals to the height of matrix, then current column finishes */
  oneColFinWire := io.ctrlPath.matrixHeight === cscCountPlusOne
  oneMatrixFinWire := io.ctrlPath.matrixWidth === columnCounterPlusOne
  /** meetNoneZeroWire will be true when current bits is not zero*/
  meetNoneZeroWire := inData.bits =/= 0.U
  /** when meet none a zero element, zeroColReg will be assigned to false, otherwise keep its value
    * After every column, it will be reset */
  zeroColReg := Mux(oneColFinWire, true.B, Mux(meetNoneZeroWire, false.B, zeroColReg))
  protected val currentZeroColumn: Bool = oneColFinWire && !meetNoneZeroWire && zeroColReg
  // true then its the first none zero element in current column
  protected val firstNoneZeroValue: Bool = meetNoneZeroWire && zeroColReg
  protected val outDataShouldValid: Bool = meetNoneZeroWire && inData.valid
  // TODO: remove `cscAdrReg =/= 0.U` for zero column
  /** address vector will emmit one element at the beginning of each column */
  protected val outAdrShouldValid: Bool = (currentZeroColumn || firstNoneZeroValue) && inData.valid && cscAdrReg =/= 0.U
  protected val endFlag: Bool = oneStreamFinRegNext || oneVectorFinRegNext
  /** when its the last element of one Pad or the whole stream, then ready will be false to stop deq from in queue
    * when any of the out queues is full (out queue.ready is false) then stop deq from in queue
    * but when out queue is full but current data is zero, then we can deq it from in queue*/
  inData.ready := !endFlag && ((outData.ready && outAdr.ready) || !meetNoneZeroWire)
  /** and both csc data and address will be zero when endFlag is true */
  outAdr.bits := Mux(endFlag, 0.U, Mux(currentZeroColumn, zeroCode.U, cscAdrReg))
  outData.bits := Mux(endFlag, 0.U, Cat(inData.bits, cscCountReg))
  /** when [[oneVectorFinRegNext]] equals to true, then pad number should add one */
  vectorNumCounter := Mux(oneStreamFinRegNext, 0.U, Mux(oneVectorFinRegNext, vectorNumPlusOne, vectorNumCounter))
  outData.valid := Mux(endFlag, true.B, outDataShouldValid)
  outAdr.valid := Mux(endFlag, true.B, outAdrShouldValid)
  cscCountReg := Mux(inData.fire(), Mux(oneColFinWire, 0.U, cscCountPlusOne), cscCountReg)
  /** if it's a zero column, then adr will keep its value */
  cscAdrReg := Mux(endFlag, 0.U, Mux(outDataShouldValid, cscAdrPlusOne, cscAdrReg))
  columnCounter := Mux(endFlag, 0.U, Mux(oneColFinWire, columnCounterPlusOne, columnCounter))
  io.outData.adrIOs.data <> Queue(outAdr, fifoSize, pipe = true, flow = true)
  io.outData.dataIOs.data <> Queue(outData, fifoSize, pipe = true, flow = true)
  if (debug) {
    io.debugIO.firstNoneZero := firstNoneZeroValue
    io.debugIO.zeroColReg := zeroColReg
    io.debugIO.cscAdrReg := cscAdrReg
    io.debugIO.columnCounter := columnCounter
    io.debugIO.endFlag := endFlag
    io.debugIO.oneVecFin := oneVectorFinRegNext
    io.debugIO.allVecFin := oneStreamFinRegNext
    io.debugIO.oneColFin := oneColFinWire
    io.debugIO.currentRow := cscCountReg
    io.debugIO.currentStreamNum := vectorNumCounter
  } else {
    io.debugIO <> DontCare
  }
}
