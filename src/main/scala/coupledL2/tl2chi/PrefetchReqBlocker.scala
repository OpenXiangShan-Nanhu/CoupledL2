package coupledL2.tl2chi
 
import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.tilelink.TLPermissions._
import coupledL2._
import coupledL2.prefetch._
import coupledL2.L2Module
import freechips.rocketchip.tilelink._
import freechips.rocketchip.util._
import freechips.rocketchip.diplomacy._

object LdState {
  val Slight    = "b00".U // Less than 50% full
  val Moderate  = "b01".U // Greater than 50% full
  val High      = "b10".U // Greater than 75% full
  val Critical  = "b11".U // Greater than 90% full
}
class PrefetchReqBlocker(implicit p: Parameters) extends L2Module with HasCHIOpcodes {
  val io = IO(new Bundle() {
    val rxdat = Input(Valid(new CHIDAT))
    val rxrsp = Input(Valid(new CHIRSP))
    val alreadyBlock = Output(Bool())
  })
  private val loadStateRxRsp  = io.rxrsp.bits.cBusy.getOrElse(0.U(3.W))(1, 0)
  private val loadStateRxDat  = io.rxdat.bits.cBusy.getOrElse(0.U(3.W))(1, 0)
  private val isBusyFromRxRsp = io.rxrsp.valid && loadStateRxRsp >= LdState.High
  private val isBusyFromRxDat = io.rxdat.valid && loadStateRxDat >= LdState.High
  private val shouldBlock = isBusyFromRxRsp || isBusyFromRxDat
  private val blockCycle = cacheParams.blockCycle
  private val counter = RegInit(0.U(log2Ceil(blockCycle + 1).W))

  when(shouldBlock) {
    counter := blockCycle.U
  }.elsewhen(counter.orR) {
    counter := counter - 1.U
  }

  io.alreadyBlock := counter.orR
}
