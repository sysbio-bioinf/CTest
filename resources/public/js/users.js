(function(){
    $( document ).ready(function() {

        var table = $('#user-table').DataTable({
            paginate: false,
            ordering: false,
            info:     false
        });

        var deleteUser = function(){
            $('#deleteDialog').modal('show');
            var user = $(this).data('user');
            $('#deleteDialog .btn-primary').off('click').on('click', function(e) {
                $.ajax({
                    url: serverRoot + "/staff/usr/" + user,
                    type: 'DELETE',
                    success: deleteSuccess,
                    error: deleteError,
                    user: user
                });
            });
        };

        $('.btn-delete-user').on('click', deleteUser);

        var successTemplate = '<div class="alert alert-success alert-dismissable"><button type="button" class="close" data-dismiss="alert" aria-hidden="true">&times;</button><% message %></div>';
        var errorTemplate = '<div class="alert alert-danger alert-dismissable"><button type="button" class="close" data-dismiss="alert" aria-hidden="true">&times;</button><% message %></div>';

        var newUserSuccess = function (e) {
            var user = e;
            user['role'] = user['role'] === ":role/user" ? "User" : ( user['role'] === ":role/reporter" ? "Reporter" : "Admin");
            var message = {
                message: "Added user " + user['username']
            };
            $('#new-user').before(Mustache.render(successTemplate, message)).each(function(){
                this.reset();
            });
            var templateEdit = $('#template_edit').html();
            var templateDelete = $('#template_delete').html();

            var editRendered = Mustache.render(templateEdit, user);
            var deleteRendered = Mustache.render(templateDelete, user);
            var data = new Array(5);
            data[0] = user.username;
            data[1] = user.fullname;
            data[2] = user.role;
            data[3] = editRendered;
            data[4] = deleteRendered;
            table.row.add(data).draw();

            var row = $('#user-table tbody tr:last');
            row.attr('id','row_'+user.username);
            var cells = row.children('td');
            cells[0].classList.add('cell-username');
            cells[1].classList.add('cell-fullname');
            cells[2].classList.add('cell-roles');

            $('#row_' + user.username + ' .btn-delete-user').on('click', deleteUser);
        };
        var newUserError = function (e) {
            var message = {
                message: e.responseJSON.error
            };
            $('#new-user').before(Mustache.render(errorTemplate, message)).each(function(){
                this.reset();
            });

        };

        $('#new-user').submit(function(e) {
            e.preventDefault();
            var formData = $(this).serializeObject();
            $.ajax({
                url: serverRoot + '/staff/usr',
                contentType: "application/json; charset=utf-8",
                type: 'POST',
                data: JSON.stringify(formData),
                success: newUserSuccess,
                error: newUserError
            });
        });

        var updateUserSuccessHandler = function(user){
            if (user['username']) {
            	if (user['fullname']) {
                    table.cell('#row_'+user['username']+' .cell-fullname').data(user['fullname']);
                }
                if (user['role']){
                    var role = user['role'] === ":role/user" ? "User" : ( user['role'] === ":role/reporter" ? "Reporter" : "Admin");
                    table.cell('#row_'+user['username']+' .cell-roles').data(role);
                }
            }
            $('#edit-dialog').modal('hide');
        };
        
        var updateUserErrorHandler = function(e){        	
        	var msg;
        	if (e.hasOwnProperty("responseJSON")) {
        		msg = {message: "Failure: " + e.responseJSON.error };
            }
            else if (e.hasOwnProperty("responseText")) {
            	msg = {message: "Failure: " + e.responseText };
            }
            else {
            	msg = {message: "Unknown reason."};
            }
        	$('#editForm').before(Mustache.render(errorTemplate, msg));
        }

        $('#edit-dialog').on('loaded.bs.modal',function() {
            $('#saveEditButton').off('click').on('click', function () {
                var formData = $('#editForm').serializeObject();
                formData.username = $('#editUsername').data('username');
                for(var prop in formData) {
                    if(formData.hasOwnProperty(prop) && (formData[prop] == null || formData[prop].length == 0)) {
                        delete formData[prop];
                    }
                }
                if (formData['username']) {
                    $.ajax({
                        url: serverRoot + '/staff/usr/' + formData['username'],
                        contentType: "application/json; charset=utf-8",
                        type: 'PUT',
                        data: JSON.stringify(formData),
                        success: updateUserSuccessHandler,
                        error: updateUserErrorHandler
                    });
                }
            });
        }).on('hidden.bs.modal', function(){
            $(this).data('bs.modal', null);
            $('.btn-delete-user').off('click').on('click', deleteUser);
        });

        var deleteSuccess = function (e) {
            table.rows( '#row_' + this.user ).remove().draw();
            var message = { message: this.user + " deleted"};
            $('#user-table').before(Mustache.render(successTemplate, message));
            $('#deleteDialog').modal('hide');
        };
        var deleteError = function (e) {
            var message;
            switch (e.status) {
                case 404:
                    message = { message: this.user + " doesn't exist" };
                    break;
                case 403:
                    message = { message: "Don't delete yourself" };
                    break;
            }
            $('#user-table').before(Mustache.render(errorTemplate, message));
            $('#deleteDialog').modal('hide');
        };
    });
}());