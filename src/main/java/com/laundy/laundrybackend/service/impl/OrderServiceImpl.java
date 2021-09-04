package com.laundy.laundrybackend.service.impl;

import com.laundy.laundrybackend.constant.Constants;
import com.laundy.laundrybackend.constant.OrderStatusEnum;
import com.laundy.laundrybackend.exception.OrderCannotBeCancelException;
import com.laundy.laundrybackend.exception.UnauthorizedException;
import com.laundy.laundrybackend.models.*;
import com.laundy.laundrybackend.models.dtos.OrderDetailResponseDTO;
import com.laundy.laundrybackend.models.dtos.OrderResponseDTO;
import com.laundy.laundrybackend.models.request.NewOrderForm;
import com.laundy.laundrybackend.models.request.OrderPaymentForm;
import com.laundy.laundrybackend.models.request.OrderServiceDetailForm;
import com.laundy.laundrybackend.repository.*;
import com.laundy.laundrybackend.service.OrderService;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.weaver.ast.Or;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.persistence.NoResultException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@Slf4j
public class OrderServiceImpl implements OrderService {
    private final ServiceDetailsRepository serviceDetailsRepository;
    private final OrderServiceDetailRepository orderServiceDetailRepository;
    private final OrderRepository orderRepository;
    private final ShipFeeRepository shipFeeRepository;
    private final UserRepository userRepository;
    private final ServiceRepository serviceRepository;

    @Autowired
    public OrderServiceImpl(ServiceDetailsRepository serviceDetailsRepository, OrderServiceDetailRepository orderServiceDetailRepository, OrderRepository orderRepository, ShipFeeRepository shipFeeRepository, UserRepository userRepository, ServiceRepository serviceRepository) {
        this.serviceDetailsRepository = serviceDetailsRepository;
        this.orderServiceDetailRepository = orderServiceDetailRepository;
        this.orderRepository = orderRepository;
        this.shipFeeRepository = shipFeeRepository;
        this.userRepository = userRepository;
        this.serviceRepository = serviceRepository;
    }

    @Override
    @Transactional
    public OrderDetailResponseDTO createNewOrder(NewOrderForm orderForm) {
        ShipFee shipFee = shipFeeRepository.getShipFeeByDistance(orderForm.getDistance());
        User currentUser = userRepository.findByUsername(SecurityContextHolder.getContext().getAuthentication().getName()).get();
        Order order = Order.builder()
                .distance(orderForm.getDistance())
                .status(OrderStatusEnum.NEW)
                .shipFee(shipFee)
                .shippingAddress(orderForm.getShippingAddress())
                .totalShipFee(orderForm.getTotalShipFee())
                .totalServiceFee(orderForm.getTotalServiceFee())
                .totalBill((orderForm.getTotalServiceFee().add(orderForm.getTotalShipFee())).multiply(Constants.VAT_VALUES))
                .user(currentUser)
                .isPaid(Boolean.FALSE)
                .pickUpPersonName(orderForm.getPickUpPersonName())
                .pickUpAddress(orderForm.getPickUpAddress())
                .pickUpPersonPhoneNumber(orderForm.getPickUpPersonPhoneNumber())
                .shippingPersonPhoneNumber(orderForm.getShippingPersonPhoneNumber())
                .shippingPersonName(orderForm.getShippingPersonName())
                .build();
        com.laundy.laundrybackend.models.Service service = serviceRepository.getById(orderForm.getServiceId());
        List<OrderServiceDetail> serviceDetails = orderServiceDetailsFromNewOrderForm(orderForm,order);
        orderServiceDetailRepository.saveAllAndFlush(serviceDetails);
        return OrderDetailResponseDTO.OrderDetailResponseDTOFromOrderAndService(order,service,serviceDetails);
    }

    @Override
    public List<OrderResponseDTO> getOrderByStatus(OrderStatusEnum status, int page, int size) {
        Pageable pageReq
                = PageRequest.of(page,size);
        List<Order> orders = orderRepository.getUserOrderByStatusAndUsername(status, SecurityContextHolder.getContext().getAuthentication().getName(),pageReq);
        List<OrderResponseDTO> responseDTOS = new ArrayList<>();
        for (Order order: orders){
            com.laundy.laundrybackend.models.Service service = serviceRepository.getServiceByOrderId(order.getId());
            responseDTOS.add(OrderResponseDTO.orderResponseDTOFromOrderAndService(order,service));
        }
        return responseDTOS;
    }

    @Override
    public OrderDetailResponseDTO getOrderDetail(Long orderId) {
        Order order = orderRepository.getById(orderId);
        com.laundy.laundrybackend.models.Service service = serviceRepository.getServiceByOrderId(orderId);
        List<OrderServiceDetail> serviceDetails = orderServiceDetailRepository.findAllByOrderId(orderId);
        return OrderDetailResponseDTO.OrderDetailResponseDTOFromOrderAndService(order,service,serviceDetails);
    }

    @Override
    @Transactional
    public void cancelOrder(Long orderId) {
        Optional<Order> optionalOrder = orderRepository.findById(orderId);
        if (optionalOrder.isPresent()){
            Order order = optionalOrder.get();
            if (!order.getUser().getUsername().equals(SecurityContextHolder.getContext().getAuthentication().getName())){
                throw new UnauthorizedException("USER NOT OWN THE ORDER");
            }
            if (!checkIfOrderIsCancelable(order)){
                throw new OrderCannotBeCancelException("ORDER CAN'T BE CANCEL");
            }else{
                order.setIsPaid(false);
                order.setStatus(OrderStatusEnum.CANCEL);
                orderRepository.save(order);
            }
        }else{
            throw new NoResultException("NO ORDER MATCH ID");
        }
    }

    @Override
    @Transactional
    public void updateOrderPayment(OrderPaymentForm orderPaymentForm) {
        PaymentInfo paymentInfo = PaymentInfo.paymentInfoFromOrderPaymentForm(orderPaymentForm);
        try{
            Order order = orderRepository.getById(orderPaymentForm.getOrderId());
            order.setIsPaid(Boolean.TRUE);
            order.setPaymentInfo(paymentInfo);
            orderRepository.save(order);
        }catch (Exception e){
            log.error(e.getMessage());
        }
    }

    @Override
    public BigDecimal getServicesFee(Double distance) {
        ShipFee shipFee = shipFeeRepository.getShipFeeByDistance(distance);
        if (shipFee == null) throw new NoResultException("NO shipfee found match distance");
        return shipFee.getFee().multiply(BigDecimal.valueOf(distance)) ;
    }

    @Override
    public BigDecimal getServicesFee(List<OrderServiceDetailForm> detailFormList) {
        BigDecimal totalServicesFee = BigDecimal.ZERO;
        List<Long> ids = detailFormList.stream().map(OrderServiceDetailForm::getServiceDetailId).collect(Collectors.toList());
        List<ServiceDetail> serviceDetails = serviceDetailsRepository.findAllById(ids);
        if (serviceDetails.isEmpty()) throw new NoResultException("NO SERVICE DETAIL");
        for (OrderServiceDetailForm serviceDetailForm: detailFormList){
            ServiceDetail serviceDetail = serviceDetails.stream()
                    .filter(detail -> serviceDetailForm.getServiceDetailId().equals(detail.getId()))
                    .findAny()
                    .orElse(null);
            totalServicesFee = totalServicesFee.add(BigDecimal.valueOf(serviceDetailForm.getQuantity()).multiply((serviceDetail.getPrice())));
        }
        return totalServicesFee;
    }

    private List<OrderServiceDetail> orderServiceDetailsFromNewOrderForm(NewOrderForm orderForm, Order order){
        List<Long> ids = orderForm.getOrderServiceDetailForms().stream().map(OrderServiceDetailForm::getServiceDetailId).collect(Collectors.toList());
        List<ServiceDetail> serviceDetails = serviceDetailsRepository.findAllById(ids);
        if (serviceDetails.isEmpty()) throw new NoResultException("NO SERVICE DETAIL");
        List<OrderServiceDetail> orderServiceDetails = new ArrayList<>();
        for (OrderServiceDetailForm serviceDetailForm: orderForm.getOrderServiceDetailForms()){
            ServiceDetail serviceDetail = serviceDetails.stream()
                    .filter(detail -> serviceDetailForm.getServiceDetailId().equals(detail.getId()))
                    .findAny()
                    .orElse(null);

            orderServiceDetails.add(OrderServiceDetail.builder()
                    .order(order)
                    .serviceDetail(serviceDetail)
                    .quantity(serviceDetailForm.getQuantity())
                    .build());
        }
        return orderServiceDetails;
    }

    private boolean checkIfOrderIsCancelable(Order order){
        if (order.getStatus().equals(OrderStatusEnum.NEW) || order.getStatus().equals(OrderStatusEnum.SHIPPER_ACCEPTED_ORDER)){
            return true;
        }
        return false;
    }
}
