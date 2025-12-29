package com.shanzhu.hospital.service.serviceImpl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.date.DatePattern;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.shanzhu.hospital.entity.po.Arrange;
import com.shanzhu.hospital.entity.po.Orders;
import com.shanzhu.hospital.entity.vo.OrderArrangeVo;
import com.shanzhu.hospital.entity.vo.OrdersPageVo;
import com.shanzhu.hospital.entity.vo.TimeSlotVo;
import com.shanzhu.hospital.mapper.ArrangeMapper;
import com.shanzhu.hospital.mapper.OrderMapper;
import com.shanzhu.hospital.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.Types;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 挂号相关 服务层
 *
 */
@Service("OrderService")
@RequiredArgsConstructor
public class OrderServiceImpl extends ServiceImpl<OrderMapper, Orders> implements OrderService {

    private final OrderMapper orderMapper;

    private final ArrangeMapper arrangeMapper;

    private final DataSource dataSource;

    /**
     * 查询挂号信息 - 分页
     *
     * @param pageNum  分页页数
     * @param pageSize 分页大小
     * @param query    查询条件
     * @return 挂号列表
     */
    @Override
    public OrdersPageVo findOrdersPages(Integer pageNum, Integer pageSize, String query) {
        //分页条件
        Page<Orders> page = new Page<>(pageNum, pageSize);

        //查询条件
        LambdaQueryWrapper<Orders> lambdaQuery = Wrappers.<Orders>lambdaQuery()
                .like(Orders::getPId, query);

        //分页查询
        IPage<Orders> iPage = this.page(page, lambdaQuery);

        //组装分页结果
        OrdersPageVo pageVo = new OrdersPageVo();
        pageVo.populatePage(iPage);

        return pageVo;
    }

    /**
     * 删除挂号单
     *
     * @param oId 挂号单id
     * @return 结果
     */
    @Override
    public Boolean deleteOrder(Integer oId) {
        return this.removeById(oId);
    }

    /**
     * 患者取消预约
     *
     * @param oId 挂号单id
     * @param pId 患者id
     * @return 结果
     */
    @Override
    public Boolean cancelOrderByPatient(Integer oId, Integer pId) {
        // 查找订单
        Orders order = this.findOrderByOid(oId);
        if (order == null) {
            throw new RuntimeException("订单不存在");
        }

        // 检查订单是否属于该患者
        if (order.getPId() != pId) {
            throw new RuntimeException("无权取消该订单");
        }

        // 检查订单状态（只有未完成的订单才能取消，oState=0表示未完成）
        // 如果 oState 为 null，视为未完成状态（可能是历史数据）
        Integer oState = order.getOState();
        if (oState != null && oState != 0) {
            throw new RuntimeException("只能取消未完成的订单");
        }
        // oState 为 null 或 0 时，都允许取消（null 视为未完成状态）

        // 检查预约时间（需要在就诊时间前才能取消）
        String oStart = order.getOStart();
        if (oStart == null || oStart.isEmpty()) {
            throw new RuntimeException("预约时间无效");
        }

        // 解析预约时间，格式：yyyy-MM-dd HH:mm-HH:mm
        // 提取开始时间部分（第一个时间段）
        String startTimeStr = oStart;
        if (oStart.length() > 11) {
            // 提取日期和时间段，例如：2025-11-19 08:30-09:30
            String datePart = oStart.substring(0, 10); // yyyy-MM-dd
            String timePart = oStart.substring(11); // HH:mm-HH:mm
            if (timePart.contains("-")) {
                String startTime = timePart.split("-")[0]; // 第一个时间段，例如：08:30
                startTimeStr = datePart + " " + startTime + ":00"; // 格式：2025-11-19 08:30:00
            }
        }

        try {
            // 解析预约开始时间
            java.time.format.DateTimeFormatter formatter = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
            java.time.LocalDateTime appointmentTime = java.time.LocalDateTime.parse(startTimeStr, formatter);
            // 获取当前时间
            java.time.LocalDateTime now = java.time.LocalDateTime.now();
            
            // 检查是否已过就诊时间
            if (now.isAfter(appointmentTime) || now.isEqual(appointmentTime)) {
                throw new RuntimeException("已过就诊时间，无法取消预约");
            }

            // 删除订单
            return this.removeById(oId);
        } catch (java.time.format.DateTimeParseException e) {
            throw new RuntimeException("预约时间格式错误");
        } catch (Exception e) {
            if (e instanceof RuntimeException) {
                throw e;
            }
            throw new RuntimeException("取消预约失败：" + e.getMessage());
        }
    }

    /**
     * 添加挂号单
     *
     * @param order 挂号单信息
     * @param arId  排班id
     * @return 结果
     */
    @Override
    public Boolean addOrder(Orders order, String arId) {
        // 处理oStart格式，确保格式正确
        String oStart = order.getOStart();
        if (oStart.length() > 22) {
            oStart = oStart.substring(0, 22);
        }
        
        // 提取日期（格式：yyyy-MM-dd）
        String date = oStart.substring(0, 10);
        
        // 提取时间段（格式：08:30-09:30）
        String timeSlot = "";
        if (oStart.length() > 11) {
            timeSlot = oStart.substring(11);
        }
        
        // 检查患者当天是否已有预约（一天只能预约一次）
        if (hasPatientOrderToday(order.getPId(), date)) {
            throw new RuntimeException("您今天已有预约，一天只能预约一次");
        }
        
        // 如果是当天预约，检查是否只能预约当前时间之后的时间段
        if (isToday(date)) {
            if (!canBookTimeSlot(timeSlot)) {
                throw new RuntimeException("当天只能预约当前时间之后的时间段");
            }
        }
        
        // 检查该时间段是否还有剩余号数
        Integer remainingCount = getRemainingCountForTimeSlot(order.getDId(), date, timeSlot);
        if (remainingCount == null || remainingCount <= 0) {
            throw new RuntimeException("该时间段号数已满，请选择其他时间段");
        }
        
        //查询患者当前时间段是否有未诊断的挂号单
        List<Orders> existOrders = lambdaQuery()
                //患者id
                .eq(Orders::getPId, order.getPId())
                //挂号时间段
                .eq(Orders::getOStart, oStart)
                //未诊断
                .eq(Orders::getOState, 0)
                .list();

        //存在未诊断的挂号单
        if(CollUtil.isNotEmpty(existOrders)){
            throw new RuntimeException("您在该时间段已有预约");
        }

        //挂号单信息
        order.setOId(RandomUtil.randomInt(1000, 9999));
        order.setOState(0);
        order.setOPriceState(0);
        order.setOStart(oStart);

        //保存挂号单
        return this.save(order);

    }

    /**
     * 查询病患挂号
     *
     * @param pId 病患id
     * @return 挂号信息
     */
    @Override
    public List<Orders> findOrderByPid(Integer pId) {
        return orderMapper.findOrderByPid(pId);
    }

    /**
     * 查询挂号
     *
     * @param oId 挂号单id
     * @return 挂号信息
     */
    @Override
    public Orders findOrderByOid(Integer oId) {
        return orderMapper.findOrderByOid(oId);
    }

    /**
     * 查看当天挂号
     *
     * @param dId    医生id
     * @param oStart 日期时间
     * @return 挂号数据
     */
    @Override
    public List<Orders> findOrderByNull(Integer dId, String oStart) {
        return orderMapper.findOrderByNull(dId, oStart);
    }

    /**
     * 更新挂号单
     * 处理预约时，会同时更新状态为已就诊（oState=1）和结束时间（oEnd）
     * 确保每个预约只能被处理一次
     *
     * @param orders 挂号单信息
     * @return 结果
     */
    @Override
    public Boolean updateOrder(Orders orders) {
        // 先检查预约是否存在且状态为待处理（oState=0或null）
        Orders existingOrder = this.getById(orders.getOId());
        if (existingOrder == null) {
            throw new RuntimeException("预约不存在");
        }
        
        // 检查预约是否已被处理过
        Integer oState = existingOrder.getOState();
        if (oState != null && oState == 1) {
            throw new RuntimeException("该预约已被处理，不能重复处理");
        }
        
        //设置挂号单状态
        orders.setOState(1);

        //设置挂号单结束时间
        orders.setOEnd(DateUtil.now());

        //更新
        return this.updateById(orders);
    }

    /**
     * 缴费
     *
     * @param oId 挂号单id
     * @return 结果
     */
    @Override
    public Boolean updatePrice(Integer oId) {
        // 先查询订单信息，保留原有费用
        Orders order = this.getById(oId);
        if (order == null) {
            return Boolean.FALSE;
        }
        
        // 更新缴费状态为已缴费，但保留原有费用（不将费用清零）
        // 只更新缴费状态，不修改费用字段
        order.setOPriceState(1);
        return this.updateById(order);
    }

    /**
     * 查询已完结挂号单
     *
     * @param pageNum  分页页数
     * @param pageSize 分页大小
     * @param pid      病患id
     * @param dId      医生id
     * @return 挂号单
     */
    @Override
    public OrdersPageVo findOrderFinish(
            Integer pageNum, Integer pageSize, String pid, Integer dId
    ) {
        //分页条件
        Page<Orders> page = new Page<>(pageNum, pageSize);

        //查询条件
        LambdaQueryWrapper<Orders> lambdaQuery = Wrappers.<Orders>lambdaQuery()
                //模糊匹配病患id
                .like(Orders::getPId, pid)
                //医生id
                .eq(Orders::getDId, dId)
                //状态1
                .eq(Orders::getOState, 1)
                //按状态降序
                .orderByDesc(Orders::getOState);

        //分页查询
        IPage<Orders> iPage = this.page(page, lambdaQuery);

        //组装结果
        OrdersPageVo pageVo = new OrdersPageVo();
        pageVo.populatePage(iPage);

        return pageVo;
    }

    /**
     * 根据dId查询挂号
     *
     * @param pageNum  分页页数
     * @param pageSize 分页大小
     * @param pId      病患id
     * @param dId      医生id
     * @return 挂号单
     */
    public OrdersPageVo findOrderByDid(
            Integer pageNum, Integer pageSize, String pId, Integer dId
    ) {
        //分页条件
        Page<Orders> page = new Page<>(pageNum, pageSize);

        //查询条件
        LambdaQueryWrapper<Orders> lambdaQuery = Wrappers.<Orders>lambdaQuery()
                //模糊匹配病患id（只有当pId不为空时才添加此条件）
                .like(org.springframework.util.StringUtils.hasText(pId), Orders::getPId, pId)
                //医生id（查询该医生的所有历史挂号，不限日期）
                .eq(Orders::getDId, dId)
                //按挂号时间倒序排列（最新的在前面）
                .orderByDesc(Orders::getOStart);

        //分页查询
        IPage<Orders> iPage = this.page(page, lambdaQuery);

        //组装结果
        OrdersPageVo pageVo = new OrdersPageVo();
        pageVo.populatePage(iPage);

        return pageVo;
    }

    /**
     * 统计挂号人数
     *
     * @return 人数
     * @Param oStart 时间
     */
    @Override
    public Integer countOrderPeople(String oStart) {
        return orderMapper.orderPeople(oStart);
    }

    /**
     * 统计今天某个医生挂号人数
     *
     * @param oStart 时间
     * @param dId    医生id
     * @return 人数
     */
    @Override
    public Integer countOrderPeopleByDid(String oStart, Integer dId) {
        return orderMapper.orderPeopleByDid(oStart, dId);
    }

    /**
     * 统计挂号男女人数
     *
     * @return 人数
     */
    public List<String> countOrderGender() {
        return orderMapper.orderGender();
    }

    /**
     * 增加诊断及医生意见
     * 处理预约时，会同时更新状态为已就诊（oState=1）和结束时间（oEnd）
     * 确保每个预约只能被处理一次
     *
     * @param order 挂号单信息
     * @return 结果
     */
    public Boolean updateOrderByAdd(Orders order) {
        // 先检查预约是否存在且状态为待处理（oState=0或null）
        Orders existingOrder = this.getById(order.getOId());
        if (existingOrder == null) {
            throw new RuntimeException("预约不存在");
        }
        
        // 检查预约是否已被处理过
        Integer oState = existingOrder.getOState();
        if (oState != null && oState == 1) {
            throw new RuntimeException("该预约已被处理，不能重复处理");
        }
        
        // 更新预约信息（包括状态和结束时间）
        // SQL中已经包含了状态检查和更新逻辑
        int updateCount = orderMapper.updateOrderByAdd(order);
        if (updateCount == 0) {
            // 如果更新失败，可能是预约已被处理或不存在
            throw new RuntimeException("更新失败，预约可能已被处理或不存在");
        }

        return Boolean.TRUE;
    }

    /**
     * 判断诊断后购买药物是否已经缴费
     *
     * @param oId 挂号单id
     * @return 结果
     */
    public Boolean findTotalPrice(int oId) {
        Orders order = this.getById(oId);
        if (order.getOTotalPrice() != 0.00) {
            order.setOPriceState(0);

            this.updateById(order);
            return Boolean.TRUE;
        }

        return Boolean.FALSE;
    }

    /**
     * 获取挂号时间段
     *
     * @param arId 排班id
     * @return 时间段
     */
    @Override
    public OrderArrangeVo findOrderTime(String arId) {
        //查询排班信息 - 使用自定义查询方法，避免 selectById 对 String 类型主键的问题
        Arrange arrange = null;
        try {
            System.out.println("Service 层开始查询，arId: " + arId + ", 类型: " + (arId != null ? arId.getClass().getName() : "null"));
            // 先尝试使用自定义查询
            arrange = arrangeMapper.selectByArId(arId);
            System.out.println("自定义查询结果: " + (arrange != null ? arrange.toString() : "null"));
            // 如果自定义查询返回 null，再尝试 selectById
            if (arrange == null) {
                System.out.println("自定义查询返回 null，尝试使用 selectById");
                arrange = arrangeMapper.selectById(arId);
                System.out.println("selectById 查询结果: " + (arrange != null ? arrange.toString() : "null"));
            }
        } catch (Exception e) {
            // 记录异常但不抛出，返回 null 让 Controller 处理
            System.err.println("查询排班信息异常，arId: " + arId + ", 错误: " + e.getMessage());
            e.printStackTrace();
            // 确保返回 null，不抛出异常
            return null;
        }
        
        if (arrange == null) {
            // 返回 null 而不是抛出异常，让 Controller 层处理
            System.out.println("未找到排班信息，arId: " + arId);
            return null;
        }

        OrderArrangeVo orderArrangeVo = new OrderArrangeVo();
        orderArrangeVo.setOrderDate(arrange.getArTime());
        
        // 计算每个时间段的剩余号数
        List<TimeSlotVo> timeSlots = calculateTimeSlots(arrange.getDId(), arrange.getArTime());
        orderArrangeVo.setTimeSlots(timeSlots);
        
        System.out.println("查询成功，返回 OrderArrangeVo: " + orderArrangeVo);
        System.out.println("时间段列表大小: " + (timeSlots != null ? timeSlots.size() : 0));
        if (timeSlots != null) {
            for (TimeSlotVo slot : timeSlots) {
                System.out.println("时间段: " + slot.getTimeSlot() + ", 剩余号数: " + slot.getRemainingCount());
            }
        }

        return orderArrangeVo;
    }
    
    /**
     * 计算每个时间段的剩余号数
     * 
     * @param dId 医生ID
     * @param date 日期（格式：yyyy-MM-dd）
     * @return 时间段列表
     */
    private List<TimeSlotVo> calculateTimeSlots(Integer dId, String date) {
        List<TimeSlotVo> timeSlots = new ArrayList<>();
        int totalCount = 10; // 每个时间段总号数
        
        // 定义所有时间段
        String[] timeSlotArray = {
            "08:30-09:30",
            "09:30-10:30",
            "10:30-11:30",
            "14:30-15:30",
            "15:30-16:30",
            "16:30-17:30"
        };
        
        for (String timeSlot : timeSlotArray) {
            TimeSlotVo vo = new TimeSlotVo();
            vo.setTimeSlot(timeSlot);
            vo.setTotalCount(totalCount);
            
            // 查询该时间段的预约数量
            Integer bookedCount = orderMapper.countOrdersByTimeSlot(dId, date, timeSlot);
            if (bookedCount == null) {
                bookedCount = 0;
            }
            
            System.out.println("时间段: " + timeSlot + ", 日期: " + date + ", 医生ID: " + dId + ", 已预约数: " + bookedCount);
            
            // 计算剩余号数
            int remainingCount = totalCount - bookedCount;
            vo.setRemainingCount(Math.max(0, remainingCount)); // 确保不为负数
            
            System.out.println("时间段: " + timeSlot + ", 剩余号数: " + vo.getRemainingCount());
            
            timeSlots.add(vo);
        }
        
        return timeSlots;
    }
    
    /**
     * 检查患者某天是否已有预约
     * 
     * @param pId 患者ID
     * @param date 日期（格式：yyyy-MM-dd）
     * @return true表示已有预约，false表示没有预约
     */
    public boolean hasPatientOrderToday(Integer pId, String date) {
        Integer count = orderMapper.countPatientOrdersByDate(pId, date);
        return count != null && count > 0;
    }
    
    /**
     * 判断指定日期是否是今天
     * 
     * @param date 日期（格式：yyyy-MM-dd）
     * @return true表示是今天，false表示不是今天
     */
    private boolean isToday(String date) {
        String today = DateUtil.formatDate(DateUtil.date());
        return today.equals(date);
    }
    
    /**
     * 判断是否可以预约该时间段（当天只能预约当前时间之后的时间段）
     * 
     * @param timeSlot 时间段（格式：08:30-09:30）
     * @return true表示可以预约，false表示不能预约
     */
    private boolean canBookTimeSlot(String timeSlot) {
        if (timeSlot == null || timeSlot.isEmpty()) {
            return false;
        }
        
        // 提取时间段的开始时间（如：08:30）
        String startTime = timeSlot.split("-")[0];
        if (startTime == null || startTime.isEmpty()) {
            return false;
        }
        
        // 获取当前时间（格式：HH:mm）
        String currentTime = DateUtil.format(DateUtil.date(), "HH:mm");
        
        // 比较时间（格式：HH:mm）
        return startTime.compareTo(currentTime) > 0;
    }
    
    /**
     * 获取某个时间段的剩余号数
     * 
     * @param dId 医生ID
     * @param date 日期（格式：yyyy-MM-dd）
     * @param timeSlot 时间段（格式：08:30-09:30）
     * @return 剩余号数
     */
    private Integer getRemainingCountForTimeSlot(Integer dId, String date, String timeSlot) {
        int totalCount = 10; // 每个时间段总号数
        Integer bookedCount = orderMapper.countOrdersByTimeSlot(dId, date, timeSlot);
        if (bookedCount == null) {
            bookedCount = 0;
        }
        return totalCount - bookedCount;
    }

    /**
     * 统计近20天科室人数
     *
     * @return 人数
     */
    @Override
    public List<String> countOrderSection() {
        //过去20天开始时间
        LocalDate beginDate = LocalDate.now().minusDays(20);
        String startTime = beginDate.format(DateTimeFormatter.ofPattern(DatePattern.NORM_DATE_PATTERN));
        String endTime = DateUtil.now();

        return this.orderMapper.orderSection(startTime, endTime);
    }

    /**
     * 调用存储过程 calc_doctor_visit_count，统计某医生完成诊疗次数。
     *
     * 对应的 MySQL 存储过程定义为：
     * <pre>
     * CREATE PROCEDURE calc_doctor_visit_count(IN p_d_id INT, OUT p_count INT)
     * BEGIN
     *   SELECT IFNULL(COUNT(o_id), 0) INTO p_count
     *   FROM orders
     *   WHERE d_id = p_d_id AND o_state = 1;
     * END;
     * </pre>
     *
     * @param dId 医生id
     * @return 完成诊疗次数（p_count）
     */
    @Override
    public Integer calcDoctorVisitCount(Integer dId) {
        try (Connection connection = dataSource.getConnection();
             CallableStatement cs = connection.prepareCall("{CALL calc_doctor_visit_count(?, ?)}")) {

            cs.setInt(1, dId);
            cs.registerOutParameter(2, Types.INTEGER);

            cs.execute();
            return cs.getInt(2);
        } catch (Exception e) {
            // 这里选择抛出 RuntimeException，让上层决定如何处理错误
            throw new RuntimeException("调用存储过程 calc_doctor_visit_count 失败", e);
        }
    }
}
