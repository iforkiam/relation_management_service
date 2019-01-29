package data.driven.erm.controller.wechatapi;

import com.alibaba.fastjson.JSONObject;
import data.driven.erm.api.PayAPI;
import data.driven.erm.business.commodity.CommodityService;
import data.driven.erm.business.order.OrderRebateService;
import data.driven.erm.business.order.OrderReceiveAddrService;
import data.driven.erm.business.order.OrderRefundDetailInfoService;
import data.driven.erm.business.order.OrderService;
import data.driven.erm.business.wechat.WechatAppInfoService;
import data.driven.erm.business.wechat.WechatUserService;
import data.driven.erm.common.Constant;
import data.driven.erm.common.WechatApiSession;
import data.driven.erm.entity.order.OrderEntity;
import data.driven.erm.entity.order.OrderReceiveAddrEntity;
import data.driven.erm.entity.order.OrderRefundDetailInfoEntity;
import data.driven.erm.entity.wechat.WechatAppInfoEntity;
import data.driven.erm.util.JSONUtil;
import data.driven.erm.util.UUIDUtil;
import data.driven.erm.vo.commodity.CommodityVO;
import data.driven.erm.vo.order.OrderDetailVO;
import data.driven.erm.vo.order.OrderVO;
import data.driven.erm.vo.pay.PayPrepayVO;
import data.driven.erm.vo.wechat.WechatUserInfoVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;
import java.util.ArrayList;
import java.util.List;

import static data.driven.erm.util.JSONUtil.putMsg;

/**
 * @author hejinkai
 * @date 2018/10/3
 */
@Controller
@RequestMapping("/wechatapi/order")
public class WechatOrderController {

    private static final Logger logger = LoggerFactory.getLogger(WechatOrderController.class);

    @Autowired
    private OrderService orderService;
    @Autowired
    private OrderReceiveAddrService orderReceiveAddrService;
    @Autowired
    private CommodityService commodityService;
    @Autowired
    private OrderRebateService orderRebateService;
    @Autowired
    private WechatUserService wechatUserService;
    @Autowired
    private PayAPI payAPI;
    @Autowired
    private WechatAppInfoService wechatAppInfoService;
    /**订单退款详情信息表Service**/
    @Autowired
    private OrderRefundDetailInfoService orderRefundDetailInfoService;

    /**
     * 立即购买，查询产品和地址
     * @param sessionID
     * @param commodityId
     * @return
     */
    @ResponseBody
    @RequestMapping(path = "/purchaseImmediately")
    public JSONObject purchaseImmediately(String sessionID, String commodityId) {
        WechatUserInfoVO wechatUserInfoVO = WechatApiSession.getSessionBean(sessionID).getUserInfo();
        JSONObject result = putMsg(true, "200", "调用成功");
        CommodityVO commodityVO = commodityService.getCommodityById(commodityId);
        result.put("commodityVO", commodityVO);
        commodityVO.setFilePath(Constant.STATIC_FILE_PATH + commodityVO.getFilePath());
//        if(commodityVO.getCommodityImageTextList() != null){
//            List<String> changePathList = new ArrayList<String>();
//            for (String filePath : commodityVO.getCommodityImageTextList()){
//                changePathList.add(Constant.STATIC_FILE_PATH + filePath);
//            }
//            commodityVO.setCommodityImageTextList(changePathList);
//        }
        //TODO 满赠
        result.put("fullOfGifts", null);
        //TODO 优惠
        List<String> discountList = new ArrayList<String>();

        if (orderService.haveOrder(wechatUserInfoVO.getAppInfoId(), wechatUserInfoVO.getWechatUserId())) {
            discountList.add("95折");
        } else {
            String inviter = wechatUserService.getInviter(wechatUserInfoVO.getAppInfoId(), wechatUserInfoVO.getWechatUserId());
            if (inviter != null && inviter.trim().length() > 0) {
                discountList.add("受邀优惠立减5%");
            }
        }

        result.put("discount", discountList);
        OrderReceiveAddrEntity addr = orderReceiveAddrService.getDefaultAddr(wechatUserInfoVO.getWechatUserId());
        result.put("addr", addr);
        return result;
    }

    /**
     * 提交订单
     *
     * @param sessionID
     * @param orderJson
     * @return
     */
    @ResponseBody
    @RequestMapping(path = "/submitOrder")
    @Transactional(rollbackFor = Exception.class)
    public JSONObject submitOrder(HttpServletRequest request, String sessionID, String orderJson,String appId,String storeId) {
        WechatUserInfoVO wechatUserInfoVO = WechatApiSession.getSessionBean(sessionID).getUserInfo();
        JSONObject result = orderService.updateOrder(orderJson, wechatUserInfoVO);
        logger.info("appId "+appId);
        logger.info("订单已插入数据库");
        if (result.getBoolean("success")) {
            //如果订单生成成功，则调用支付统一下单接口
            logger.info("调用支付统一下单接口");
            JSONObject resulJson = orderService.submissionUnifiedorder(request,
                    appId,wechatUserInfoVO.getOpenId(), result.getString("orderId"),storeId);
            if (!resulJson.getBoolean("success")) {
                throw new RuntimeException(resulJson.getString("msg"));
            } else {
                return resulJson;
            }
        }
        return result;
    }

    /**
     * 完成支付
     *
     * @param sessionID
     * @param orderId
     * @return
     */
    @ResponseBody
    @RequestMapping(path = "/completionOfPayment")
    public JSONObject completionOfPayment(String sessionID, String orderId) {
        WechatUserInfoVO wechatUserInfoVO = WechatApiSession.getSessionBean(sessionID).getUserInfo();
        orderService.updateOrderState(orderId, wechatUserInfoVO.getWechatUserId(), 1);
        String userId = wechatUserService.getInviter(wechatUserInfoVO.getAppInfoId(), wechatUserInfoVO.getWechatUserId());
        if (userId != null) {
            OrderVO order = orderService.getOrderById(orderId, wechatUserInfoVO.getAppInfoId(), wechatUserInfoVO.getWechatUserId());
            if (order != null && order.getRebate().intValue() == 1) {
                orderRebateService.insertOrderRebate(order, wechatUserInfoVO.getAppInfoId(), userId);
            }
        }
        return putMsg(true, "200", "调用成功");
    }

    /**
     * @description 申请退款
     * @author lxl
     * @date 2019-01-29 15:00
     * @param sessionID session信息
     * @param  orderId 订单id
     * @param storeId 门店id
     * @return
     */
    @ResponseBody
    @RequestMapping(path = "/orderRefund")
    public JSONObject orderRefund( String sessionID, String orderId, String storeId){
        WechatUserInfoVO wechatUserInfoVO = WechatApiSession.getSessionBean(sessionID).getUserInfo();
        //得到订单信息
        OrderEntity orderEntity = orderService.findOrderByOrderId(orderId);
        //得到小程序信息
        WechatAppInfoEntity wechatAppInfoEntity = wechatAppInfoService.getAppInfoEntity(wechatUserInfoVO.getAppInfoId());
        //初始化订单退款详情信息表实体
        String outRefundNo = UUIDUtil.getUUID();
        logger.info("商户退款单号： "+outRefundNo);
        OrderRefundDetailInfoEntity rrderRefundDetailInfoEntity = new OrderRefundDetailInfoEntity(wechatAppInfoEntity.getAppid(),
                wechatUserInfoVO.getWechatUserId(),storeId,"",outRefundNo,orderId,orderEntity.getRealPayment(),
                orderEntity.getRealPayment());
        //插入订单退款详情
        JSONObject resultJson = orderRefundDetailInfoService.insertOrderRefundDetailInfoEntity(rrderRefundDetailInfoEntity);
        if (resultJson.getBoolean("success")){
            JSONObject refundJson = orderService.orderRefund(wechatAppInfoEntity.getAppid(),storeId,"",orderId,
                    outRefundNo,orderEntity.getRealPayment(),orderEntity.getRealPayment());
            logger.info("调用申请退款接口返回的信息： "+refundJson.toString());
            if (refundJson.getBoolean("success")){
                //修改订单状态3为退款成功
                orderService.updateOrderState(orderId, wechatUserInfoVO.getWechatUserId(), 3);
            }
            return refundJson;
        }else{
            return resultJson;
        }
    }


    /**
     * 完成订单
     *
     * @param sessionID
     * @param orderId
     * @return
     */
    @ResponseBody
    @RequestMapping(path = "/completionOfOrder")
    public JSONObject completionOfOrder(String sessionID, String orderId) {
        WechatUserInfoVO wechatUserInfoVO = WechatApiSession.getSessionBean(sessionID).getUserInfo();
        orderService.updateOrderState(orderId, wechatUserInfoVO.getWechatUserId(), 2);
        return putMsg(true, "200", "调用成功");
    }

    /**
     * 根据用户查询订单列表
     *
     * @param sessionID
     * @return
     */
    @ResponseBody
    @RequestMapping(path = "/findOrderList")
    public JSONObject findOrderList(String sessionID) {
        WechatUserInfoVO wechatUserInfoVO = WechatApiSession.getSessionBean(sessionID).getUserInfo();
        List<OrderVO> orderList = orderService.findOrderList(wechatUserInfoVO.getAppInfoId(), wechatUserInfoVO.getWechatUserId());
        if (orderList != null && orderList.size() > 0) {
            for (OrderVO orderVO : orderList) {
                List<OrderDetailVO> detailList = orderVO.getDetailList();
                if (detailList != null && detailList.size() > 0) {
                    for (OrderDetailVO orderDetailVO : detailList) {
                        orderDetailVO.setFilePath(Constant.STATIC_FILE_PATH + orderDetailVO.getFilePath());
                    }
                }
            }
        }

        JSONObject result = putMsg(true, "200", "调用成功");
        result.put("data", JSONUtil.replaceNull(orderList));
        return result;
    }

    @RequestMapping(path = "/prepay")
    @ResponseBody
    public JSONObject getPrepayInfo( String sessionID,String appId,String storeId,String outTradeNo) {
        logger.info("开始获取统一下单信息");
        JSONObject result;
        String msg = "";
        result = payAPI.getPrepay(appId, storeId, outTradeNo);
        if (result == null) {
            result = new JSONObject();
            result.put("success", false);
            msg = "获取下单信息失败，请重试";
            result.put("msg", msg);
            logger.error(msg);
            return result;
        }
        if (result.getBoolean("success")) {
            logger.info("成功获取统一订单信息");
        } else {
            logger.error(result.getString("msg"));
        }
        return result;
    }


}
