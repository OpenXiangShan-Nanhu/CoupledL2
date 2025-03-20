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
class PrefetchReqBlocker(implicit p: Parameters) extends L2Module with HasCHIOpcodes{
  val io = IO(new Bundle() {
    val block = Input(Bool())
    val reqFromPref = Input(new PrefetchReq)
    val reqToSlice = Output(new PrefetchReq)
  })
  val emptyReq = Wire(new PrefetchReq)
  emptyReq := DontCare
  io.reqToSlice := Mux(io.block, emptyReq, io.reqFromPref)
}
