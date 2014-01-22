//**************************************************************************
// Functional Units
//--------------------------------------------------------------------------
//
// Christopher Celio
// 2013 Mar 10
//
// If regfile bypassing is disabled, then the functional unit must do its own
// bypassing in here on the WB stage (i.e., bypassing the io.resp.data)

// QUESTIONS
//    is there a way to have a plug-able output bundle?  (adder_out...)
//
//    is there a way to pass in the FU logic itself? or do I have to wrap all possible instances?


package BOOM
{

import Chisel._
import Node._

import rocket.ALU._
import uncore.constants.AddressConstants._
import uncore.constants.MemoryOpConstants._


object FUCode
{
   // bit mask, since a given execution pipeline may support multiple functional units
   val FUC_SZ = 7
   val FU_X    = Bits( 0, FUC_SZ)
   val FU_ALU  = Bits( 1, FUC_SZ)
   val FU_BRU  = Bits( 2, FUC_SZ)
   val FU_CNTR = Bits( 4, FUC_SZ)
   val FU_PCR  = Bits( 8, FUC_SZ)
   val FU_MULD = Bits(16, FUC_SZ)
   val FU_MEM  = Bits(32, FUC_SZ)
}
import FUCode._
 
// TODO if a branch unit... how to add extra to IO in subclass?

class FunctionalUnitIo(num_stages: Int, num_bypass_stages: Int)(implicit conf: BOOMConfiguration) extends Bundle()
{
   val req     = (new DecoupledIO(new FuncUnitReq)).flip
   val resp    = (new DecoupledIO(new FuncUnitResp))

   val brinfo  = new BrResolutionInfo().asInput()

   val bypass  = new BypassData(num_bypass_stages).asOutput()
   
   val br_unit = new BranchUnitResp().asOutput()

   val get_rob_pc = new Bundle 
   {
      val rob_idx = UInt(OUTPUT, ROB_ADDR_SZ) 
      val curr_pc = UInt(INPUT, XPRLEN)
      val next_val= Bool(INPUT)
      val next_pc = UInt(INPUT, XPRLEN)
   }
}

class FuncUnitReq extends Bundle()
{
   val uop = new MicroOp()
   val rs1_data = Bits(width = XPRLEN) 
   val rs2_data = Bits(width = XPRLEN)

   val kill = Bool() // kill everything
}

class FuncUnitResp extends Bundle()
{
   val uop = new MicroOp()
   val data = Bits(width = XPRLEN)  
   val xcpt = (new rocket.HellaCacheExceptions)
//   val xcpt = Bits(width = EXC_CAUSE_SZ)
}
 
class BypassData(num_bypass_ports:Int) extends Bundle()
{
   val valid = Vec.fill(num_bypass_ports){ Bool() }
   val uop   = Vec.fill(num_bypass_ports){ new MicroOp() }
   val data  = Vec.fill(num_bypass_ports){ Bits(width = XPRLEN) }

   def get_num_ports: Int = num_bypass_ports
}
 
class BranchUnitResp extends Bundle()
{
   // TODO maybe just add pc_sel, btb_mispredict to branch resolution
   // TODO REMOVE WIDTH once Chisel gets its act together
   val pc_sel         = Bits(width = PC_PLUS4.getWidth)
   val taken          = Bool()
   val btb_mispredict = Bool()
   val rob_idx        = UInt(width = ROB_ADDR_SZ)

   val brjmp_target    = UInt(width = XPRLEN)
   val jump_reg_target = UInt(width = XPRLEN)
   val pc              = UInt(width = XPRLEN) // TODO this isn't really a branch_unit thing
   val pc_plus4        = UInt(width = XPRLEN) // TODO this isn't really a branch_unit thing

   val brinfo = new BrResolutionInfo()

   val debug_btb_pred = Bool() // just for debug, did the BTB and BHT predict taken?
   val debug_bht_pred = Bool()
}

abstract class FunctionalUnit(is_pipelined: Boolean 
                              , num_stages: Int
                              , num_bypass_stages: Int
                              , has_branch_unit : Boolean = false)
                              (implicit conf: BOOMConfiguration)
                              extends Module
{
   val io = new FunctionalUnitIo(num_stages, num_bypass_stages)
//   val is_var_latency = false
}



abstract class PipelinedFunctionalUnit(val num_stages: Int, 
                                       val num_bypass_stages: Int,
                                       val earliest_bypass_stage: Int,
                                       is_branch_unit: Boolean = false)
                                       (implicit conf: BOOMConfiguration) 
                                       extends FunctionalUnit(is_pipelined = true
                                                              , num_stages = num_stages
                                                              , num_bypass_stages = num_bypass_stages
                                                              , has_branch_unit = is_branch_unit)
{
   // pipelined functional unit is always ready
   io.req.ready := Bool(true)

   val r_valids = Vec.fill(num_stages) { Reg(init = Bool(false)) }
   val r_uops   = Vec.fill(num_stages) { Reg(outType =  new MicroOp()) } 


   if (num_stages >= 1)
   {
      // handle incoming request
      r_valids(0) := io.req.valid && !IsKilledByBranch(io.brinfo, io.req.bits.uop) && !io.req.bits.kill
      r_uops(0)   := io.req.bits.uop
      r_uops(0).br_mask := GetNewBrMask(io.brinfo, io.req.bits.uop)

      // handle middle of the pipeline
      for (i <- 1 until num_stages)
      {
         r_valids(i) := r_valids(i-1) && !IsKilledByBranch(io.brinfo, r_uops(i-1)) && !io.req.bits.kill
         r_uops(i)   := r_uops(i-1)
         r_uops(i).br_mask := GetNewBrMask(io.brinfo, r_uops(i-1))

         io.bypass.uop(i-1) := r_uops(i-1)
      }

      // handle outgoing (branch could still kill it)
      io.resp.valid    := r_valids(num_stages-1) && !IsKilledByBranch(io.brinfo, r_uops(num_stages-1)) && !io.req.bits.kill
      io.resp.bits.uop := r_uops(num_stages-1)
      io.resp.bits.uop.br_mask := GetNewBrMask(io.brinfo, r_uops(num_stages-1))
   }
   else
   {
      require (num_stages == 0)
      // pass req straight through to response
      
      io.resp.valid    := io.req.valid && !IsKilledByBranch(io.brinfo, io.req.bits.uop) && !io.req.bits.kill
      io.resp.bits.uop := io.req.bits.uop
      io.resp.bits.uop.br_mask := GetNewBrMask(io.brinfo, io.req.bits.uop)
   }

   // bypassing (TODO allow bypass vector to have a different size from num_stages)
   if (num_bypass_stages > 0 && earliest_bypass_stage == 0)
   {
      io.bypass.uop(0) := io.req.bits.uop
      
      for (i <- 1 until num_bypass_stages)
      {
         io.bypass.uop(i) := r_uops(i-1)

      }
   }
    

}

class ALUUnit(is_branch_unit: Boolean = false)
                                          (implicit conf: BOOMConfiguration) 
                                          extends PipelinedFunctionalUnit(num_stages = 1
                                                                           , num_bypass_stages = 2
                                                                           , earliest_bypass_stage = 0
                                                                           , is_branch_unit = is_branch_unit)
{

 
   val uop = io.req.bits.uop

   // TODO only let branch unit do this (one per machine)
   io.get_rob_pc.rob_idx := uop.rob_idx
   val uop_pc_ = io.get_rob_pc.curr_pc

   // immediate generation
   val imm_xprlen = ImmGen(uop.imm_packed, uop.ctrl.imm_sel)

   // operand 2 select 
   // TODO op1 mux just for auipc? 
   val op1_data = Mux((io.req.bits.uop.uopc === uopAUIPC) , Cat((uop_pc_ >> UInt(12)), Bits(0,12)), io.req.bits.rs1_data)
   val op2_data = Mux((io.req.bits.uop.ctrl.op2_sel.toUInt === OP2_IMM) , Sext(imm_xprlen, conf.rc.xprlen), io.req.bits.rs2_data)

   implicit val rc = conf.rc
   val alu = Module(new rocket.ALU())

   alu.io.in1 := op1_data.toUInt
   alu.io.in2 := op2_data.toUInt
   alu.io.fn  := io.req.bits.uop.ctrl.op_fcn
   alu.io.dw  := io.req.bits.uop.ctrl.fcn_dw

   // TODO branch unit: move to a different class and instantiate?
   val pc_plus4 = (uop_pc_ + UInt(4))(XPRLEN-1,0)

   if (is_branch_unit)
   {
      val rs1 = io.req.bits.rs1_data
      val rs2 = io.req.bits.rs2_data
      val br_eq  = (rs1 === rs2)
      val br_ltu = (rs1.toUInt < rs2.toUInt)
      val br_lt  = (~(rs1(XPRLEN-1) ^ rs2(XPRLEN-1)) & br_ltu | 
                      rs1(XPRLEN-1) & ~rs2(XPRLEN-1)).toBool

      io.br_unit.pc_sel := Lookup(io.req.bits.uop.ctrl.br_type, PC_PLUS4,
               Array(   BR_N  -> PC_PLUS4, 
                        BR_NE -> Mux(!br_eq,  PC_BRJMP, PC_PLUS4),
                        BR_EQ -> Mux( br_eq,  PC_BRJMP, PC_PLUS4),
                        BR_GE -> Mux(!br_lt,  PC_BRJMP, PC_PLUS4),
                        BR_GEU-> Mux(!br_ltu, PC_BRJMP, PC_PLUS4),
                        BR_LT -> Mux( br_lt,  PC_BRJMP, PC_PLUS4),
                        BR_LTU-> Mux( br_ltu, PC_BRJMP, PC_PLUS4),
                        BR_J  -> PC_BRJMP,
                        BR_JR -> PC_JALR
                        ))



      io.br_unit.taken      := io.req.valid &&
                            uop.is_br_or_jmp &&
                            (io.br_unit.pc_sel != PC_PLUS4) 

      // assumption is BHT prediction and BTB prediction are mutually exclusive
      io.br_unit.brinfo.mispredict := io.req.valid && 
                                      uop.is_br_or_jmp &&
                                       (((io.br_unit.taken ^ (uop.br_prediction.isBrTaken() === TAKEN)) && !uop.btb_pred_taken) || // BHT was wrong
                                       (!io.br_unit.taken && uop.btb_pred_taken) || // BTB was wrong
                                       (io.br_unit.taken && uop.btb_pred_taken && (io.br_unit.pc_sel === PC_JALR) && 
                                          (!io.get_rob_pc.next_val || (io.get_rob_pc.next_pc != io.br_unit.jump_reg_target))) // BTB was right, but wrong target for JALR
                                       )                         

//                                       ((uop.btb_pred_taken && !io.br_unit.taken) || // BTB was wrong (BHT was set to false)
//                                        (!uop.btb_pred_taken    (uop.br_prediction.isBrTaken() === TAKEN) && !uop.br_unit.taken)
//                                       )
      
      assert (!(io.req.valid && uop.uopc === uopJAL && io.br_unit.brinfo.mispredict), "JAL was predicted as not taken")

      // need to tell the BTB it mispredicted and needs to update
      // TODO currently only telling BTB about branches and JAL, should also use JALR
      io.br_unit.btb_mispredict := io.req.valid && 
                                    uop.is_br_or_jmp && 
//                                    !uop.is_ret && let jr ra be held in the btb too
                                       // push/call is rd=x1
                                       // pop/return is rd=x0, rs1=x1
//                                    !uop.is_jump && 
                                    (io.br_unit.taken ^ uop.btb_pred_taken)

      io.br_unit.brinfo.valid   := io.req.valid && uop.is_br_or_jmp
      io.br_unit.brinfo.mask    := UInt(1) << uop.br_tag
      io.br_unit.brinfo.exe_mask:= uop.br_mask
      io.br_unit.brinfo.tag     := uop.br_tag
      io.br_unit.brinfo.rob_idx := uop.rob_idx
      io.br_unit.brinfo.ldq_idx := uop.ldq_idx
      io.br_unit.brinfo.stq_idx := uop.stq_idx

      // Branch/Jump Target Calculation
      io.br_unit.brjmp_target   := Sext(imm_xprlen, conf.rc.xprlen) + uop_pc_ // TODO use ALU for this addition?
      io.br_unit.jump_reg_target := alu.io.adder_out
      io.br_unit.pc             := uop_pc_
      io.br_unit.pc_plus4       := pc_plus4
      io.br_unit.debug_btb_pred := uop.btb_pred_taken
      io.br_unit.debug_bht_pred := uop.br_prediction.taken
   }


   val out_data =  Bits(width = XPRLEN)
   if (is_branch_unit)
      out_data := Mux(io.req.bits.uop.ctrl.wb_sel === WB_PC4, pc_plus4, alu.io.out)
   else
      out_data := alu.io.out


   // Bypass (bypass in Exe0 stage)
   // for the ALU, we can bypass after the first stage
   require (num_stages == 1)  
   require (num_bypass_stages == 2)  
   io.bypass.valid(0) := io.req.valid 
   io.bypass.data (0) := out_data
   // we must also bypass the WB stage
   io.bypass.valid(1) := io.resp.valid 
   io.bypass.data (1) := io.resp.bits.data

   // Response
   val reg_data = Reg(outType = Bits(width = XPRLEN)) 
   reg_data := out_data
   io.resp.bits.data := reg_data
      
   // Exceptions
   io.resp.bits.xcpt.ma.ld := Bool(false)
   io.resp.bits.xcpt.ma.st := Bool(false)
   io.resp.bits.xcpt.pf.ld := Bool(false)
   io.resp.bits.xcpt.pf.st := Bool(false)
}


// passes in base+imm to calculate addresses, and passes store data, to the LSU
class MemAddrCalcUnit()(implicit conf: BOOMConfiguration) extends PipelinedFunctionalUnit(num_stages = 0
                                                                                   , num_bypass_stages = 0
                                                                                   , earliest_bypass_stage = 0
                                                                                   , is_branch_unit = false)
{
   // perform address calculation
   implicit val rc = conf.rc
   val alu = Module(new rocket.ALU())

   alu.io.in1 := io.req.bits.rs1_data.toUInt
   alu.io.in2 := Cat(io.req.bits.uop.imm_packed(19,8)).toSInt //uses special packed imm format

   alu.io.fn  := FN_ADD
   alu.io.dw  := DW_XPR
                   
   val adder_out = alu.io.adder_out
   val ea_sign = Mux(adder_out(VADDR_BITS-1), ~adder_out(63,VADDR_BITS) === UInt(0), 
                                                       adder_out(63,VADDR_BITS) != UInt(0))
   val effective_address = Cat(ea_sign, adder_out(VADDR_BITS-1,0)).toUInt         
                               
   // TODO only use one register read port
   io.resp.bits.data := Mux(io.req.bits.uop.uopc === uopSTD, io.req.bits.rs2_data,
                                                             effective_address)

   // Handle misaligned exceptions
   val typ = io.req.bits.uop.mem_typ
   val misaligned = 
      (((typ === MT_H) || (typ === MT_HU)) && (effective_address(0) != Bits(0))) ||
      (((typ === MT_W) || (typ === MT_WU)) && (effective_address(1,0) != Bits(0))) ||
      ((typ === MT_D) && (effective_address(2,0) != Bits(0)))
    
   val ma_ld = io.req.valid && io.req.bits.uop.uopc === uopLD && misaligned
   val ma_st = io.req.valid && io.req.bits.uop.uopc === uopSTA && misaligned

   io.resp.bits.xcpt.ma.ld := ma_ld
   io.resp.bits.xcpt.ma.st := ma_st
   io.resp.bits.xcpt.pf.ld := Bool(false)
   io.resp.bits.xcpt.pf.st := Bool(false)


}
 

// unpipelined, can only hold a single MicroOp at a time
// assumes at least one register between request and response
abstract class UnPipelinedFunctionalUnit()
                                       (implicit conf: BOOMConfiguration) 
                                       extends FunctionalUnit(is_pipelined = false
                                                            , num_stages = 1
                                                            , num_bypass_stages = 0
                                                            , has_branch_unit = false)
{
   val r_uop = Reg(outType = new MicroOp()) 

   val do_kill = Bool()
   do_kill := io.req.bits.kill

   when (io.req.fire())
   {
      // update incoming uop
      do_kill := IsKilledByBranch(io.brinfo, io.req.bits.uop)
      r_uop := io.req.bits.uop
      r_uop.br_mask := GetNewBrMask(io.brinfo, io.req.bits.uop)
   }
   .otherwise
   {
      do_kill := IsKilledByBranch(io.brinfo, r_uop)
      r_uop.br_mask := GetNewBrMask(io.brinfo, r_uop)
   }


   // assumes at least one pipeline register between request and response
   io.resp.bits.uop := r_uop

//   io.bypass.valid(0) := Bool(false) // io.resp.valid
//   io.bypass.uop(0)   := io.resp.bits.uop
//   io.bypass.data(0)  := io.resp.bits.data
}
 

class MulDivUnit(implicit conf: BOOMConfiguration) extends UnPipelinedFunctionalUnit
{
   implicit val rc = conf.rc
   val muldiv = Module(new rocket.MulDiv(mulUnroll = if (conf.rc.fastMulDiv) 8 else 1, earlyOut = conf.rc.fastMulDiv))
   
   // request
   muldiv.io.req.valid    := io.req.valid 
   muldiv.io.req.bits.dw  := io.req.bits.uop.ctrl.fcn_dw
   muldiv.io.req.bits.fn  := io.req.bits.uop.ctrl.op_fcn 
   muldiv.io.req.bits.in1 := io.req.bits.rs1_data
   muldiv.io.req.bits.in2 := io.req.bits.rs2_data
   io.req.ready           := muldiv.io.req.ready
   
   // handle pipeline kills and branch misspeculations
   muldiv.io.kill         := this.do_kill 

   // response
   io.resp.valid          := muldiv.io.resp.valid
   muldiv.io.resp.ready   := io.resp.ready 
   io.resp.bits.data      := muldiv.io.resp.bits.data
   
   // exceptions
   io.resp.bits.xcpt.ma.ld := Bool(false)
   io.resp.bits.xcpt.ma.st := Bool(false)
   io.resp.bits.xcpt.pf.ld := Bool(false)
   io.resp.bits.xcpt.pf.st := Bool(false)
}

} 
